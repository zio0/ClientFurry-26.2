package win.furryhook.feature.impl.combat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.context.RotationContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.render.Render3DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.player.AutoToolFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.mixin.accessor.MultiPlayerGameModeAccessor;
import win.furryhook.utils.FriendManager;
import win.furryhook.utils.combat.MultipointUtil;
import win.furryhook.utils.render.world.WorldMeshRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * CrystalAura — порт tech.javelin CrystalAura 1в1 под furryhook API.
 * Ротация сайлент (как KillAura): гейты «текущего взгляда» читают серверную ротацию
 * через {@link RotationContext}, ротация держится каждый тик (аналог 999-hold Javelin).
 */
public final class CrystalAuraFeature extends Feature implements MinecraftContext {

    private final ModeSetting rotate = register(new ModeSetting("Ротация", "Vanilla", "Vanilla", "Без ротации"));
    @Getter
    private final BooleanSetting autoKrot = register(new BooleanSetting("Делать подкоп", true));
    private final BooleanSetting safeYourSelf = register(new BooleanSetting("Не взрывать себя", false));

    private int ticksToBack;
    private int lastSlot = -1;

    // Пошаговая ставка против Грима: слот на обсидиан/кристалл переключаем сейчас
    // (с форс-синком SetCarriedItem — сервер узнаёт слот до клика), сам useItemOn
    // делаем следующим тиком в начале onTick. pendingPlaceHit != null — слот уже
    // переключён, клик ожидается.
    private BlockHitResult pendingPlaceHit;
    private BlockPos pendingVisualPos;

    @Getter
    private PlaceData lastData;
    private final ConcurrentLinkedQueue<VisualData> visualDatas = new ConcurrentLinkedQueue<>();

    @Getter
    private float[] rotation;

    public CrystalAuraFeature() {
        super("CrystalAura", "Автоматически ставит обсидиан, кристаллы и подкапывает", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        RotationContext.disengage();
        ticksToBack = 0;
        rotation = null;
        lastData = null;
        pendingPlaceHit = null;
        pendingVisualPos = null;
        if (mc.gameMode != null) {
            try {
                mc.gameMode.stopDestroyBlock();
            } catch (Throwable ignored) {
            }
        }
        if (lastSlot != -1 && mc.player != null) {
            mc.player.getInventory().setSelectedSlot(lastSlot);
        }
        lastSlot = -1;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer self = mc.player;
        ClientLevel world = mc.level;
        if (self == null || world == null || mc.gameMode == null) return;
        if (self.isUsingItem()) return;

        if (ticksToBack > 0) ticksToBack--;
        if (ticksToBack == 0 && lastSlot != -1) {
            self.getInventory().setSelectedSlot(lastSlot);
            lastSlot = -1;
        }

        // Фаза ставки: слот переключён в прошлый тик, Грим применил смену слота — клик.
        // Return, чтобы в этом тике не наложился новый свитч на useItemOn (снова
        // same-tick churn, который Грим флагает).
        if (pendingPlaceHit != null) {
            BlockHitResult hit = pendingPlaceHit;
            BlockPos vis = pendingVisualPos;
            pendingPlaceHit = null;
            pendingVisualPos = null;
            // Грим сверяет ротацию именно в момент клика (GrimPlace). Ротация была задана
            // на тике свята слота, но чтобы сервер гарантированно видел взгляд на блок
            // ровно в тик useItemOn (иначе "софт-плейс из воздуха" → кик), форсим её здесь.
            if (rotation != null && rotate.is("Vanilla")) {
                RotationContext.setRotation(rotation[0], rotation[1]);
            }
            if (mc.gameMode != null) {
                mc.gameMode.useItemOn(self, InteractionHand.MAIN_HAND, hit);
                self.swing(InteractionHand.MAIN_HAND);
            }
            if (vis != null && !hasVisual(vis)) {
                visualDatas.add(new VisualData(vis, System.currentTimeMillis(), 0.2F, 0.8F, 1.0F));
            }
            return;
        }

        Player target = findTarget(self, world);

        int obsSlot = findHotbar(self, Items.OBSIDIAN);
        int crysSlot = findHotbar(self, Items.END_CRYSTAL);
        if (obsSlot == -1 || crysSlot == -1) {
            lastData = null;
            releaseRotation();
            return;
        }

        if (target != null) {
            // Все сущности мира в боксе вокруг — НЕ entitiesForRendering(). Иначе CrystalOptimizer
            // (который режет отрисовку дальних кристаллов) выкидывает их из rendering-list,
            // и аура их вообще не видит: "аура не работает, пока включён оптимайзер".
            List<Entity> entities = world.getEntitiesOfClass(
                    Entity.class,
                    self.getBoundingBox().inflate(16.0D),
                    e -> e.isAlive() && !e.isSpectator());

            PlaceData obsidianData = findBestObsidian(target, world, self, entities);
            PlaceData crystalData = findBestCrystal(target, world, self, entities);
            PlaceData breakData = findBestBreak(target, world, self, entities);

            PlaceData finalData = obsidianData;
            if (crystalData.damage() > finalData.damage()) finalData = crystalData;
            if (breakData.damage() > finalData.damage() && autoKrot.getValue()) finalData = breakData;
            if (obsidianData.placeResult() == PlaceResult.AWAITING) finalData = obsidianData;

            if (lastData == null || lastData.placeResult() != PlaceResult.EXPLOSION) lastData = finalData;
        }

        if (lastData == null || lastData.placeResult() == PlaceResult.AWAITING) {
            releaseRotation();
            return;
        }

        if (lastData.damage() == 0) {
            lastData = null;
            releaseRotation();
            return;
        }

        // ---------- explosion (бьём кристалл) ----------
        if (lastData.placeResult() == PlaceResult.EXPLOSION) {
            BlockPos blockPos = lastData.blockPos();
            Direction direction = lastData.face();
            Entity crystal = lastData.entity();

            if (blockPos != null && direction != null && crystal instanceof EndCrystal) {
                float[] rot = calculate(getFacePoint(blockPos, direction), self);
                if (raycastBox(rot, blockPos, 5.5F, self) != null && rayTrace(rot, 3.0D, crystal.getBoundingBox(), self)) {
                    if (rotate.is("Vanilla")) RotationContext.setRotation(rot[0], rot[1]);
                    mc.gameMode.attack(self, crystal);
                    self.swing(InteractionHand.MAIN_HAND);
                    lastData = null;
                    releaseRotation();
                    return;
                }
            }

            if (crystal instanceof EndCrystal) {
                Vec3 nearest = MultipointUtil.getNearestPoint(crystal, 0.0D, 0.05D);
                float[] rotCrystal = calculate(nearest, self);
                if (isLookingAtEntity(rotCrystal, crystal, 3.0D, self)) {
                    rotation = rotCrystal;
                    if (rotate.is("Без ротации") || isLookingAtEntity(crystal, 3.0D, self)) {
                        mc.gameMode.attack(self, crystal);
                        self.swing(InteractionHand.MAIN_HAND);
                    }
                    if (rotate.is("Vanilla")) RotationContext.setRotation(rotCrystal[0], rotCrystal[1]);
                }
            }

            lastData = null;
            releaseRotation();
            return;
        }

        // ---------- place obsidian / crystal / break ----------
        BlockPos blockPos = lastData.blockPos();
        if (blockPos == null) {
            lastData = null;
            releaseRotation();
            return;
        }
        Direction direction = lastData.face();
        if (direction == null) {
            lastData = null;
            releaseRotation();
            return;
        }

        float[] rot = calculate(getFacePoint(blockPos, direction), self);

        if (raycastBox(rot, blockPos, 5.5F, self) != null) {
            if (rotate.is("Без ротации") || raycastBoxLook(blockPos, 5.5F, self) != null) {
                if (lastData.placeResult() == PlaceResult.BREAK) {
                    if (findBestTool(self, world.getBlockState(blockPos)) == -1) {
                        lastData = null;
                        releaseRotation();
                        return;
                    }
                    AutoToolFeature autoTool = FeatureManager.INSTANCE.getFeature(AutoToolFeature.class);
                    if (autoTool != null) {
                        autoTool.swapForBlock(world.getBlockState(blockPos));
                    }
                    if (!world.getBlockState(blockPos).isAir()) {
                        if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc && acc.getDestroyDelay() <= 1) {
                            mc.gameMode.startDestroyBlock(blockPos, direction);
                            self.swing(InteractionHand.MAIN_HAND);
                        }
                        if (!world.getBlockState(blockPos).isAir()) {
                            if (mc.gameMode.continueDestroyBlock(blockPos, direction)) {
                                self.swing(InteractionHand.MAIN_HAND);
                            }
                        }
                    } else {
                        mc.gameMode.stopDestroyBlock();
                    }
                    if (!hasVisual(blockPos)) {
                        visualDatas.add(new VisualData(blockPos, System.currentTimeMillis(), 0.8F, 0.1F, 0.1F));
                    }
                } else {
                    int slot = findHotbar(self, Items.OBSIDIAN);
                    if (lastData.placeResult() == PlaceResult.PLACE_CRYSTAL) slot = findHotbar(self, Items.END_CRYSTAL);
                    if (slot != -1) {
                        if (lastSlot == -1) lastSlot = self.getInventory().getSelectedSlot();
                        // Грим: свитч слота и useItemOn в одном тике флагается — слот
                        // переключаем сейчас (клиент реально берёт предмет в руку, сервер
                        // узнаёт через явный SetCarriedItem), клик делаем следующим тиком.
                        ticksToBack = 3;
                        self.getInventory().setSelectedSlot(slot);
                        if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                            acc.invokerEnsureHasSentCarriedItem();
                        }
                        BlockPos vis = blockPos.relative(direction);
                        if (lastData.placeResult() == PlaceResult.PLACE_CRYSTAL) vis = blockPos.above();
                        pendingPlaceHit = new BlockHitResult(
                                Vec3.atCenterOf(blockPos), getFaceDirection(rot, self, blockPos), blockPos, false);
                        pendingVisualPos = vis;
                        lastData = null;
                    }
                }
            }
            rotation = rot;
        } else {
            releaseRotation();
            lastData = null;
        }

        if (rotation != null && rotate.is("Vanilla")) {
            RotationContext.setRotation(rotation[0], rotation[1]);
        }
    }

    /**
     * Сброс накопленной ротации после взрыва/ставки и на холостых тиках. Без этого
     * RotationContext так и остаётся active и сервер продолжает получать зафиксированный
     * взгляд в точку уже взорванного кристалла ("пустая ротация на воздух") — Грим это
     * флагает и кикает.
     */
    private void releaseRotation() {
        rotation = null;
        RotationContext.clear();
    }

    // ================= target =================

    private Player findTarget(LocalPlayer self, ClientLevel world) {
        Player best = null;
        double bestDist = 8.0D * 8.0D;
        for (Player p : world.players()) {
            if (p == self) continue;
            if (!p.isAlive() || p.isRemoved()) continue;
            if (p.isSpectator() || p.isCreative()) continue;
            if (FriendManager.INSTANCE.isFriend(p.getGameProfile().name())) continue;
            double d = self.distanceToSqr(p);
            if (d > 64.0D) continue;
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // ================= три поиска 1в1 =================

    private PlaceData findBestObsidian(Player entity, ClientLevel world, LocalPlayer self, List<Entity> entities) {
        BlockPos bestPanPos = null;
        double bestPanDamage = 0;
        Direction bestFace = null;

        BlockPos basePos = self.blockPosition();

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos checkPos = basePos.offset(x, y, z);

                    BlockState state = world.getBlockState(checkPos);
                    if (!state.getBlock().defaultBlockState().isSolid()) continue;

                    Direction face = getBestVisibleFaceToObsidian(checkPos, self, world);
                    if (face == null) continue;

                    float[] rot = calculate(getFacePoint(checkPos, face), self);
                    BlockHitResult raycast = raycastBox(rot, checkPos, 5.5F, self);
                    if (raycast == null || raycast.getDirection() != face) continue;

                    BlockPos placePos = checkPos.relative(face);
                    if (placePos.getY() >= (int) entity.getY()) continue;

                    BlockPos crystalPos = placePos.above();
                    if (!world.getBlockState(placePos).isAir()) continue;

                    if (blockedObsidian(entities, placePos) == ReturnResult.PLAYER) continue;
                    if (blockedObsidian(entities, crystalPos) == ReturnResult.PLAYER) continue;

                    if (!canReachBox(new AABB(
                            placePos.getX() - 0.5, placePos.getY(), placePos.getZ() - 0.5,
                            placePos.getX() + 2, placePos.getY() + 1.5, placePos.getZ() + 1.5), 3.0D, self)) continue;

                    double damage = ghostDamage(Vec3.atCenterOf(crystalPos), entity, placePos, world) + 19 - 6;
                    if (damage <= bestPanDamage || damage < 25) continue;

                    double selfDamage = ghostDamage(Vec3.atCenterOf(crystalPos), self, placePos, world);
                    if (!selfDamageAllowed(selfDamage)) continue;

                    if (blockedObsidian(entities, crystalPos) == ReturnResult.ITEM)
                        return new PlaceData(null, null, 0, PlaceResult.AWAITING, null);

                    bestPanDamage = damage;
                    bestPanPos = checkPos;
                    bestFace = face;
                }
            }
        }

        BlockPos bestCrystalPos = null;
        Direction bestCrystalFace = null;
        EndCrystal bestCrystal = null;
        double bestCrystalDamage = 0;

        for (Entity entity1 : entities) {
            if (!(entity1 instanceof EndCrystal crystal)) continue;

            double damage = explosionDamage(crystal.position().add(0, 0.5, 0), entity, world) + 30;
            if (damage <= bestCrystalDamage) continue;

            double selfDamage = explosionDamage(crystal.position().add(0, 0.5, 0), self, world);
            if (!selfDamageAllowed(selfDamage)) continue;

            BlockPos pos = crystal.blockPosition().below();
            Direction face = Direction.UP;

            Vec3 rotatePoint = getFacePoint(pos, face);
            float[] rotBlock = calculate(rotatePoint, self);
            BlockHitResult raycast = raycastBox(rotBlock, pos, 5.5F, self);
            if (raycast != null && raycast.getDirection() == face
                    && rayTrace(rotBlock, 3.0D, crystal.getBoundingBox(), self)) {
                bestCrystalPos = pos;
                bestCrystalFace = face;
            }

            float[] rot = calculate(MultipointUtil.getNearestPoint(crystal, 0.0D, 0.05D), self);
            if (!isLookingAtEntity(rot, crystal, 3.0D, self) && bestCrystalPos == null) continue;

            bestCrystalDamage = damage;
            bestCrystal = crystal;
        }

        if (bestCrystalDamage > bestPanDamage) {
            return new PlaceData(bestCrystalPos, bestCrystalFace, bestCrystalDamage, PlaceResult.EXPLOSION, bestCrystal);
        }

        return new PlaceData(bestPanPos, bestFace, bestPanDamage, PlaceResult.PLACE_OBSIDIAN, null);
    }

    private PlaceData findBestCrystal(Player entity, ClientLevel world, LocalPlayer self, List<Entity> entities) {
        BlockPos bestPanPos = null;
        double bestPanDamage = 0;
        Direction bestFace = null;

        BlockPos basePos = self.blockPosition();

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos checkPos = basePos.offset(x, y, z);
                    if (checkPos.getY() >= (int) entity.getY()) continue;

                    BlockState state = world.getBlockState(checkPos);
                    if (state.getBlock() != Blocks.OBSIDIAN && state.getBlock() != Blocks.BEDROCK) continue;

                    Direction face = getBestVisibleFace(checkPos, self);
                    if (face == null) continue;

                    BlockPos placePos = checkPos.above();

                    float[] rot = calculate(getFacePoint(checkPos, face), self);
                    BlockHitResult raycast = raycastBox(rot, checkPos, 5.5F, self);
                    if (raycast == null || raycast.getDirection() != face) continue;

                    float[] rot2 = calculate(getFacePoint(checkPos, Direction.UP), self);
                    BlockHitResult raycast2 = raycastBox(rot2, checkPos, 5.5F, self);
                    if (raycast2 != null && raycast2.getDirection() == Direction.UP) face = Direction.UP;

                    if (blockedCrystal(entities, placePos)) continue;

                    if (!canReachBox(new AABB(
                            placePos.getX() - 0.5, placePos.getY(), placePos.getZ() - 0.5,
                            placePos.getX() + 2, placePos.getY() + 1.5, placePos.getZ() + 1.5), 3.0D, self)) continue;

                    double damage = explosionDamage(Vec3.atCenterOf(placePos), entity, world) + 19;
                    if (damage <= bestPanDamage || damage < 31) continue;

                    double selfDamage = explosionDamage(Vec3.atCenterOf(placePos), self, world);
                    if (!selfDamageAllowed(selfDamage)) continue;

                    bestPanDamage = damage;
                    bestPanPos = checkPos;
                    bestFace = face;
                }
            }
        }

        return new PlaceData(bestPanPos, bestFace, bestPanDamage, PlaceResult.PLACE_CRYSTAL, null);
    }

    private PlaceData findBestBreak(Player entity, ClientLevel world, LocalPlayer self, List<Entity> entities) {
        BlockPos bestPanPos = null;
        double bestPanDamage = 0;
        Direction bestFace = null;
        BlockPos bestPanPosBreak = null;
        double bestPanDamageBreak = 0;
        Direction bestFaceBreak = null;

        BlockPos basePos = self.blockPosition();

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos checkPos = basePos.offset(x, y, z);
                    if (checkPos.getY() >= (int) entity.getY()) continue;

                    BlockState cs = world.getBlockState(checkPos);
                    if (!cs.getBlock().defaultBlockState().isSolid()) continue;

                    Direction face = getBestVisibleFace(checkPos, self);
                    if (face == null) continue;

                    float[] rot = calculate(getFacePoint(checkPos, face), self);
                    BlockHitResult raycast = raycastBox(rot, checkPos, 5.5F, self);
                    if (raycast == null || raycast.getDirection() != face) continue;

                    BlockPos placePos = checkPos.above();
                    if (blockedBreak(entities, placePos)) continue;

                    if (!canReachBox(new AABB(
                            placePos.getX() - 0.5, placePos.getY(), placePos.getZ() - 0.5,
                            placePos.getX() + 2, placePos.getY() + 1.5, placePos.getZ() + 1.5), 3.0D, self)) continue;

                    if (!world.getBlockState(placePos).isAir()) {
                        int bestSlot = -1;
                        int bestSlot2 = -1;
                        {
                            BlockState state = world.getBlockState(placePos);
                            float bestSpeed = 1.0F;
                            for (int slot = 0; slot < 36; slot++) {
                                ItemStack stack = self.getInventory().getItem(slot);
                                if (stack.isEmpty()) continue;
                                float speed = stack.getDestroySpeed(state);
                                if (speed > bestSpeed) {
                                    bestSpeed = speed;
                                    bestSlot = slot;
                                }
                            }
                        }
                        {
                            BlockState state = world.getBlockState(checkPos);
                            float bestSpeed = 1.0F;
                            for (int slot = 0; slot < 36; slot++) {
                                ItemStack stack = self.getInventory().getItem(slot);
                                if (stack.isEmpty()) continue;
                                float speed = stack.getDestroySpeed(state);
                                if (speed > bestSpeed) {
                                    bestSpeed = speed;
                                    bestSlot2 = slot;
                                }
                            }
                        }
                        if (bestSlot == -1 || (world.getBlockState(checkPos).getBlock() != Blocks.OBSIDIAN && bestSlot2 == -1))
                            continue;

                        double damage = ignoreDamage(Vec3.atCenterOf(placePos), entity, placePos, world) + 19 - 12;
                        if (damage <= bestPanDamageBreak || damage < 19) continue;

                        double selfDamage = ignoreDamage(Vec3.atCenterOf(placePos), self, placePos, world);
                        if (!selfDamageAllowed(selfDamage)) continue;

                        bestPanDamageBreak = damage;
                        bestPanPosBreak = placePos;
                        bestFaceBreak = face;
                        continue;
                    }

                    BlockState state = world.getBlockState(checkPos);
                    int bestSlot = -1;
                    float bestSpeed = 1.0F;
                    for (int slot = 0; slot < 36; slot++) {
                        ItemStack stack = self.getInventory().getItem(slot);
                        if (stack.isEmpty()) continue;
                        float speed = stack.getDestroySpeed(state);
                        if (speed > bestSpeed) {
                            bestSpeed = speed;
                            bestSlot = slot;
                        }
                    }
                    if (bestSlot == -1) continue;

                    double damage = explosionDamage(Vec3.atCenterOf(placePos), entity, world) + 19 - 12;
                    if (damage <= bestPanDamage || damage < 19) continue;

                    double selfDamage = explosionDamage(Vec3.atCenterOf(placePos), self, world);
                    if (!selfDamageAllowed(selfDamage)) continue;

                    bestPanDamage = damage;
                    bestPanPos = checkPos;
                    bestFace = face;
                }
            }
        }

        if (bestPanDamageBreak > bestPanDamage)
            return new PlaceData(bestPanPosBreak, bestFaceBreak, bestPanDamageBreak, PlaceResult.BREAK, null);
        else return new PlaceData(bestPanPos, bestFace, bestPanDamage, PlaceResult.BREAK, null);
    }

    // ================= блокировки =================

    /**
     * Гейт самоподрыва. Выключено — старый порог в 30 HP (почти никогда не срабатывает).
     * Включено — кандидат отбрасывается, если расчётная порча по себе больше полусердечка.
     */
    private boolean selfDamageAllowed(double selfDamage) {
        if (safeYourSelf.getValue()) {
            return selfDamage <= 0.5D;
        }
        return selfDamage <= 30.0D;
    }

    private ReturnResult blockedObsidian(List<Entity> entities, BlockPos crystalPos) {
        for (Entity entity : entities) {
            AABB blockBox = new AABB(
                    crystalPos.getX(), crystalPos.getY() - 0.2F, crystalPos.getZ(),
                    crystalPos.getX() + 1.0, crystalPos.getY() + 2.2, crystalPos.getZ() + 1.0);
            if (entity instanceof ItemEntity && !entity.onGround() && entity.getBoundingBox().intersects(blockBox)) {
                return ReturnResult.ITEM;
            }
            if ((entity instanceof Player || entity instanceof EndCrystal) && intersectsBox(crystalPos, entity)) {
                return ReturnResult.PLAYER;
            }
        }
        return ReturnResult.NONE;
    }

    private boolean blockedCrystal(List<Entity> entities, BlockPos placePos) {
        for (Entity entity : entities) {
            if (intersectsBox(placePos, entity)) return true;
        }
        return false;
    }

    private boolean blockedBreak(List<Entity> entities, BlockPos placePos) {
        for (Entity entity : entities) {
            if (intersectsBox(placePos, entity) && !(entity instanceof ItemEntity)) return true;
        }
        return false;
    }

    private boolean intersectsBox(BlockPos pos, Entity entity) {
        AABB crystalBox = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1);
        return crystalBox.intersects(entity.getBoundingBox());
    }

    private boolean hasVisual(BlockPos blockPos) {
        for (VisualData visualData : visualDatas) {
            if (visualData.blockPos.equals(blockPos)) return true;
        }
        return false;
    }

    // ================= ротации / рейкасты =================

    private float[] calculate(Vec3 target, LocalPlayer self) {
        Vec3 eye = self.getEyePosition();
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        return new float[]{yaw, pitch};
    }

    private Vec3 getFacePoint(BlockPos pos, Direction face) {
        return new Vec3(
                pos.getX() + 0.5 + face.getStepX() * 0.5001,
                pos.getY() + 0.5 + face.getStepY() * 0.5001,
                pos.getZ() + 0.5 + face.getStepZ() * 0.5001);
    }

    /**
     * «Текущий взгляд» для гейтов: в Vanilla-режиме это серверная (сайлент) ротация,
     * которую камера не показывает; в «Без ротации» — реальный взгляд камеры игрока.
     */
    private Vec3 lookVec(LocalPlayer self) {
        if (rotate.is("Vanilla") && RotationContext.isActive()) {
            return Vec3.directionFromRotation(RotationContext.getServerPitch(), RotationContext.getServerYaw());
        }
        Camera cam = mc.gameRenderer.mainCamera();
        return Vec3.directionFromRotation(cam.xRot(), cam.yRot());
    }

    /** Рейкаст бокса блока по явной ротации (RaycastUtils.raycastBlock(Rotation, ...) Javelin). */
    private BlockHitResult raycastBox(float[] rot, BlockPos targetPos, double maxDistance, LocalPlayer self) {
        Vec3 eye = self.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(rot[1], rot[0]);
        Vec3 end = eye.add(dir.scale(maxDistance));
        AABB blockBox = new AABB(targetPos);
        Optional<Vec3> hitOpt = blockBox.clip(eye, end);
        if (hitOpt.isEmpty()) return null;
        Vec3 hitVec = hitOpt.get();
        Direction side = getHitSide(hitVec, targetPos);
        return new BlockHitResult(hitVec, side, targetPos, false);
    }

    /** Рейкаст бокса блока по «текущему взгляду» (RaycastUtils.raycastBlock(BlockPos, ...) Javelin). */
    private BlockHitResult raycastBoxLook(BlockPos targetPos, double maxDistance, LocalPlayer self) {
        Vec3 eye = self.getEyePosition();
        Vec3 dir = lookVec(self);
        Vec3 end = eye.add(dir.scale(maxDistance));
        AABB blockBox = new AABB(targetPos);
        Optional<Vec3> hitOpt = blockBox.clip(eye, end);
        if (hitOpt.isEmpty()) return null;
        Vec3 hitVec = hitOpt.get();
        Direction side = getHitSide(hitVec, targetPos);
        return new BlockHitResult(hitVec, side, targetPos, false);
    }

    private Direction getHitSide(Vec3 hitVec, BlockPos pos) {
        double dx = hitVec.x - (pos.getX() + 0.5);
        double dy = hitVec.y - (pos.getY() + 0.5);
        double dz = hitVec.z - (pos.getZ() + 0.5);
        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);
        double absDz = Math.abs(dz);
        if (absDx > absDy && absDx > absDz) return dx > 0 ? Direction.EAST : Direction.WEST;
        else if (absDy > absDz) return dy > 0 ? Direction.UP : Direction.DOWN;
        else return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Javelin WorldUtils.getBestVisibleFace — ближайшая видимая грань. */
    private Direction getBestVisibleFace(BlockPos pos, LocalPlayer self) {
        Vec3 eye = self.getEyePosition();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            float[] rot = calculate(getFacePoint(pos, face), self);
            if (raycastBox(rot, pos, 4.5F, self) == null) continue;
            double dist = eye.distanceToSqr(getFacePoint(pos, face));
            if (dist < bestDist) {
                bestDist = dist;
                best = face;
            }
        }
        return best;
    }

    /** Javelin WorldUtils.getBestVisibleFaceToObsidian — грань с макс. уроном ghost-блока (по себе). */
    private Direction getBestVisibleFaceToObsidian(BlockPos pos, LocalPlayer self, ClientLevel world) {
        Direction best = null;
        double bestDist = 0;
        for (Direction face : Direction.values()) {
            BlockPos sidePos = pos.relative(face);
            if (!world.getBlockState(sidePos).isAir()) continue;
            float[] rot = calculate(getFacePoint(pos, face), self);
            if (raycastBox(rot, pos, 4.5F, self) == null) continue;
            double damage = ghostDamage(Vec3.atCenterOf(sidePos.above()), self, sidePos, world);
            if (damage > bestDist) {
                bestDist = damage;
                best = face;
            }
        }
        return best;
    }

    private boolean rayTrace(float[] rot, double reach, AABB box, LocalPlayer self) {
        Vec3 eye = self.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(rot[1], rot[0]);
        Vec3 end = eye.add(dir.scale(reach));
        return box.contains(eye) || box.clip(eye, end).isPresent();
    }

    private boolean isLookingAtEntity(float[] rot, Entity entity, double reach, LocalPlayer self) {
        return rayTrace(rot, reach, entity.getBoundingBox(), self);
    }

    /** Javelin RaycastUtils.isLookingAtEntity(Entity, range) — по «текущему взгляду». */
    private boolean isLookingAtEntity(Entity entity, double reach, LocalPlayer self) {
        Vec3 eye = self.getEyePosition();
        Vec3 dir = lookVec(self);
        Vec3 end = eye.add(dir.scale(reach));
        AABB box = entity.getBoundingBox();
        return box.contains(eye) || box.clip(eye, end).isPresent();
    }

    private boolean canReachBox(AABB box, double reach, LocalPlayer self) {
        return box.distanceToSqr(self.getEyePosition()) <= reach * reach;
    }

    // ================= грань для клика (Javelin DirectionUtil.getFaceDirection) =================

    private Direction getFaceDirection(float[] rot, LocalPlayer self, BlockPos targetPos) {
        Vec3 eyePos = self.getEyePosition();
        Vec3 lookVec = Vec3.directionFromRotation(rot[1], rot[0]);

        double cx = targetPos.getX() + 0.5;
        double cy = targetPos.getY() + 0.5;
        double cz = targetPos.getZ() + 0.5;
        double minX = targetPos.getX();
        double minY = targetPos.getY();
        double minZ = targetPos.getZ();
        double maxX = targetPos.getX() + 1.0;
        double maxY = targetPos.getY() + 1.0;
        double maxZ = targetPos.getZ() + 1.0;

        Direction closestFace = null;
        double closestT = Double.MAX_VALUE;

        double t = tryHitPlaneY(eyePos, lookVec, maxY, minX, maxX, minZ, maxZ);
        if (t > 0 && t < closestT) {
            closestT = t;
            closestFace = Direction.UP;
        }
        t = tryHitPlaneY(eyePos, lookVec, minY, minX, maxX, minZ, maxZ);
        if (t > 0 && t < closestT) {
            closestT = t;
            closestFace = Direction.DOWN;
        }
        t = tryHitPlaneZ(eyePos, lookVec, minZ, minX, maxX, minY, maxY);
        if (t > 0 && t < closestT) {
            closestT = t;
            closestFace = Direction.NORTH;
        }
        t = tryHitPlaneZ(eyePos, lookVec, maxZ, minX, maxX, minY, maxY);
        if (t > 0 && t < closestT) {
            closestT = t;
            closestFace = Direction.SOUTH;
        }
        t = tryHitPlaneX(eyePos, lookVec, minX, minY, maxY, minZ, maxZ);
        if (t > 0 && t < closestT) {
            closestT = t;
            closestFace = Direction.WEST;
        }
        t = tryHitPlaneX(eyePos, lookVec, maxX, minY, maxY, minZ, maxZ);
        if (t > 0 && t < closestT) {
            closestT = t;
            closestFace = Direction.EAST;
        }

        if (closestFace == null) {
            closestFace = fallbackDirection(eyePos, cx, cy, cz);
        }

        return closestFace;
    }

    private static double tryHitPlaneY(Vec3 eye, Vec3 dir, double yVal, double minX, double maxX, double minZ, double maxZ) {
        if (dir.y == 0) return -1;
        double t = (yVal - eye.y) / dir.y;
        if (t <= 0) return -1;
        double hitX = eye.x + t * dir.x;
        double hitZ = eye.z + t * dir.z;
        if (hitX >= minX && hitX <= maxX && hitZ >= minZ && hitZ <= maxZ) {
            return t;
        }
        return -1;
    }

    private static double tryHitPlaneZ(Vec3 eye, Vec3 dir, double zVal, double minX, double maxX, double minY, double maxY) {
        if (dir.z == 0) return -1;
        double t = (zVal - eye.z) / dir.z;
        if (t <= 0) return -1;
        double hitX = eye.x + t * dir.x;
        double hitY = eye.y + t * dir.y;
        if (hitX >= minX && hitX <= maxX && hitY >= minY && hitY <= maxY) {
            return t;
        }
        return -1;
    }

    private static double tryHitPlaneX(Vec3 eye, Vec3 dir, double xVal, double minY, double maxY, double minZ, double maxZ) {
        if (dir.x == 0) return -1;
        double t = (xVal - eye.x) / dir.x;
        if (t <= 0) return -1;
        double hitY = eye.y + t * dir.y;
        double hitZ = eye.z + t * dir.z;
        if (hitY >= minY && hitY <= maxY && hitZ >= minZ && hitZ <= maxZ) {
            return t;
        }
        return -1;
    }

    private static Direction fallbackDirection(Vec3 eye, double cx, double cy, double cz) {
        double dx = eye.x - cx;
        double dy = eye.y - cy;
        double dz = eye.z - cz;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else if (ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    // ================= инвентарь =================

    private int findHotbar(LocalPlayer self, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = self.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) return i;
        }
        return -1;
    }

    private int findBestTool(LocalPlayer self, BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = self.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    // ================= урон (CrystalDamageCalculator Javelin 1в1) =================

    private static final double DIAMETER = 12.0D;

    /** Javelin getExplosionDamage(..., false): HIT-тип + минимум урона 1, без early-return на exposure == 0. */
    private double explosionDamage(Vec3 source, LivingEntity target, ClientLevel world) {
        if (world.getDifficulty() == Difficulty.PEACEFUL || target == null) return 0;
        if (!new AABB(
                Mth.floor(source.x - 11.0D), Mth.floor(source.y - 11.0D), Mth.floor(source.z - 11.0D),
                Mth.floor(source.x + 13.0D), Mth.floor(source.y + 13.0D), Mth.floor(source.z + 13.0D))
                .intersects(target.getBoundingBox())) return 0;
        if (target.isInvulnerable()) return 0;
        double distExposure = target.position().distanceToSqr(source) / 144.0D;
        if (distExposure > 1.0D) return 0;
        double finalExposure = (1.0D - distExposure) * exposure(source, target.getBoundingBox(), world);
        float toDamage = (float) Math.floor((finalExposure * finalExposure + finalExposure) / 2.0D * 7.0D * DIAMETER + 1.0D);
        return reductions(toDamage, target, world);
    }

    /** Javelin getDamageOfGhostBlock: сам блок БУДУЩЕГО обсидиана считается твёрдым. */
    private double ghostDamage(Vec3 source, LivingEntity target, BlockPos ghost, ClientLevel world) {
        if (world.getDifficulty() == Difficulty.PEACEFUL) return 0;
        if (!new AABB(
                Mth.floor(source.x - DIAMETER - 1.0D), Mth.floor(source.y - DIAMETER - 1.0D), Mth.floor(source.z - DIAMETER - 1.0D),
                Mth.floor(source.x + DIAMETER + 1.0D), Mth.floor(source.y + DIAMETER + 1.0D), Mth.floor(source.z + DIAMETER + 1.0D))
                .intersects(target.getBoundingBox())) return 0;
        if (target.isInvulnerable()) return 0;
        double distExposure = target.position().distanceToSqr(source) / 144.0D;
        if (distExposure > 1.0D) return 0;
        double finalExposure = (1.0D - distExposure) * exposureOverride(source, target.getBoundingBox(), world, ghost, null);
        float toDamage = (float) Math.floor((finalExposure * finalExposure + finalExposure) / 2.0D * 7.0D * DIAMETER + 1.0D);
        return reductions(toDamage, target, world);
    }

    /** Javelin getDamageIgnoringBlock: разбиваемый блок считается воздухом. */
    private double ignoreDamage(Vec3 source, LivingEntity target, BlockPos ignore, ClientLevel world) {
        if (world.getDifficulty() == Difficulty.PEACEFUL || target == null || ignore == null) return 0;
        if (!new AABB(
                Mth.floor(source.x - DIAMETER - 1.0D), Mth.floor(source.y - DIAMETER - 1.0D), Mth.floor(source.z - DIAMETER - 1.0D),
                Mth.floor(source.x + DIAMETER + 1.0D), Mth.floor(source.y + DIAMETER + 1.0D), Mth.floor(source.z + DIAMETER + 1.0D))
                .intersects(target.getBoundingBox())) return 0;
        if (target.isInvulnerable()) return 0;
        double distExposure = target.position().distanceToSqr(source) / 144.0D;
        if (distExposure > 1.0D) return 0;
        double finalExposure = (1.0D - distExposure) * exposureOverride(source, target.getBoundingBox(), world, null, ignore);
        float toDamage = (float) Math.floor((finalExposure * finalExposure + finalExposure) / 2.0D * 7.0D * DIAMETER + 1.0D);
        return reductions(toDamage, target, world);
    }

    /** Сложность, броня/прочность, сопротивление, протекшн-энчанты — как в Javelin. */
    private float reductions(float toDamage, LivingEntity target, ClientLevel world) {
        if (world.getDifficulty() == Difficulty.EASY) {
            toDamage = Math.min(toDamage / 2.0F + 1.0F, toDamage);
        } else if (world.getDifficulty() == Difficulty.HARD) {
            toDamage = toDamage * 3.0F / 2.0F;
        }

        float armor = (float) Math.floor(target.getArmorValue());
        float toughness = 0.0F;
        var attr = target.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (attr != null) toughness = (float) attr.getValue();
        toDamage = getDamageAfterArmor(toDamage, armor, toughness);

        MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            float r = 25.0F - (resistance.getAmplifier() + 1) * 5.0F;
            toDamage = Math.max(toDamage * r / 25.0F, 0.0F);
        }

        if (toDamage <= 0.0F) return 0.0F;

        int protAmount = getProtectionTotal(target, world);
        if (protAmount > 0) {
            toDamage = toDamage * (1.0F - Mth.clamp(protAmount, 0.0F, 20.0F) / 25.0F);
        }

        return toDamage;
    }

    private static float getDamageAfterArmor(float damage, float armor, float toughness) {
        float i = 2.0F + toughness / 4.0F;
        float j = Mth.clamp(armor - damage / i, armor * 0.2F, 20.0F);
        return damage * (1.0F - j / 25.0F);
    }

    private int getProtectionTotal(LivingEntity target, ClientLevel world) {
        int amount = 0;
        amount += protectionFor(target.getItemBySlot(EquipmentSlot.HEAD), world);
        amount += protectionFor(target.getItemBySlot(EquipmentSlot.CHEST), world);
        amount += protectionFor(target.getItemBySlot(EquipmentSlot.LEGS), world);
        amount += protectionFor(target.getItemBySlot(EquipmentSlot.FEET), world);
        return amount;
    }

    private int protectionFor(ItemStack stack, ClientLevel world) {
        return enchantLevel(Enchantments.BLAST_PROTECTION, stack, world) * 2 + enchantLevel(Enchantments.PROTECTION, stack, world);
    }

    private int enchantLevel(ResourceKey<Enchantment> key, ItemStack stack, ClientLevel world) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            return EnchantmentHelper.getItemEnchantmentLevel(
                    world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), stack);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Javelin getExposure(source, box): фиксированные шаги + смещение. */
    private double exposure(Vec3 source, AABB box, ClientLevel world) {
        double d = 0.4545454446934474D;
        double e = 0.21739130885479366D;
        double f = 0.4545454446934474D;
        int i = 0;
        int j = 0;
        for (double k = 0.0D; k <= 1.0D; k += d) {
            for (double l = 0.0D; l <= 1.0D; l += e) {
                for (double m = 0.0D; m <= 1.0D; m += f) {
                    double n = Mth.lerp(k, box.minX, box.maxX);
                    double o = Mth.lerp(l, box.minY, box.maxY);
                    double p = Mth.lerp(m, box.minZ, box.maxZ);
                    if (isVisible(new Vec3(n + 0.045454555306552624D, o, p + 0.045454555306552624D), source, world, null, null)) i++;
                    j++;
                }
            }
        }
        return (double) i / (double) j;
    }

    /** Javelin getExposureGhost/getExposureIgnoreBlock: шаги из размеров бокса, гост/игнор-подстановки. */
    private double exposureOverride(Vec3 source, AABB box, ClientLevel world, BlockPos ghost, BlockPos ignore) {
        double d = 1.0D / ((box.maxX - box.minX) * 2.0D + 1.0D);
        double e = 1.0D / ((box.maxY - box.minY) * 2.0D + 1.0D);
        double f = 1.0D / ((box.maxZ - box.minZ) * 2.0D + 1.0D);
        if (d < 0.0D || e < 0.0D || f < 0.0D) return 0.0D;
        double g = (1.0D - Math.floor(1.0D / d) * d) / 2.0D;
        double h = (1.0D - Math.floor(1.0D / f) * f) / 2.0D;
        int i = 0;
        int j = 0;
        for (double k = 0.0D; k <= 1.0D; k += d) {
            for (double l = 0.0D; l <= 1.0D; l += e) {
                for (double m = 0.0D; m <= 1.0D; m += f) {
                    double n = Mth.lerp(k, box.minX, box.maxX);
                    double o = Mth.lerp(l, box.minY, box.maxY);
                    double p = Mth.lerp(m, box.minZ, box.maxZ);
                    if (isVisible(new Vec3(n + g, o, p + h), source, world, ghost, ignore)) i++;
                    j++;
                }
            }
        }
        return (double) i / (double) j;
    }

    private boolean isVisible(Vec3 start, Vec3 end, ClientLevel world, BlockPos ghost, BlockPos ignore) {
        return raycastShapes(start, end, world, ghost, ignore) == null;
    }

    /** Буквальная замена Javelin BlockView.raycast с подстановкой состояний ghost/ignore. */
    private BlockHitResult raycastShapes(Vec3 start, Vec3 end, ClientLevel world, BlockPos ghost, BlockPos ignore) {
        return BlockGetter.traverseBlocks(start, end, world,
                (ctx, pos) -> {
                    BlockState state;
                    if (ghost != null && pos.equals(ghost)) state = Blocks.OBSIDIAN.defaultBlockState();
                    else if (ignore != null && pos.equals(ignore)) state = Blocks.AIR.defaultBlockState();
                    else state = world.getBlockState(pos);
                    return state.getCollisionShape(world, pos).clip(start, end, pos);
                },
                ctx -> null);
    }

    // ================= визуалы =================

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (visualDatas.isEmpty() || mc.level == null) return;
        long now = System.currentTimeMillis();
        visualDatas.removeIf(v -> now - v.createdAt > 350L);

        List<WorldMeshRenderer.Line> lines = new ArrayList<>();
        List<WorldMeshRenderer.Ring> rings = new ArrayList<>();
        List<WorldMeshRenderer.PlaneRect> rects = new ArrayList<>();
        for (VisualData v : visualDatas) {
            float age = (now - v.createdAt) / 300.0F;
            if (age >= 1.0F) continue;
            float alpha = 1.0F - age;
            int color = toArgb(v.r, v.g, v.b, alpha);
            int edge = toArgb(v.r, v.g, v.b, Math.min(1.0F, alpha + 0.3F));
            box(lines, rects, new AABB(v.blockPos), color, edge);
        }
        if (!lines.isEmpty() || !rects.isEmpty()) {
            WorldMeshRenderer.render(new WorldMeshRenderer.WorldMesh(lines, rings, rects), true);
        }
    }

    private static int toArgb(float r, float g, float b, float a) {
        int ai = (int) (Mth.clamp(a, 0.0F, 1.0F) * 255.0F);
        int ri = (int) (Mth.clamp(r, 0.0F, 1.0F) * 255.0F);
        int gi = (int) (Mth.clamp(g, 0.0F, 1.0F) * 255.0F);
        int bi = (int) (Mth.clamp(b, 0.0F, 1.0F) * 255.0F);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static void box(List<WorldMeshRenderer.Line> lines, List<WorldMeshRenderer.PlaneRect> rects, AABB box, int fill, int edge) {
        Vec3 c = box.getCenter();
        double hx = (box.maxX - box.minX) / 2.0D;
        double hy = (box.maxY - box.minY) / 2.0D;
        double hz = (box.maxZ - box.minZ) / 2.0D;
        rects.add(new WorldMeshRenderer.PlaneRect(new Vec3(box.minX, c.y, c.z), new Vec3(0, 1, 0), new Vec3(0, 0, 1), hy, hz, fill));
        rects.add(new WorldMeshRenderer.PlaneRect(new Vec3(box.maxX, c.y, c.z), new Vec3(0, 1, 0), new Vec3(0, 0, 1), hy, hz, fill));
        rects.add(new WorldMeshRenderer.PlaneRect(new Vec3(c.x, box.minY, c.z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), hx, hz, fill));
        rects.add(new WorldMeshRenderer.PlaneRect(new Vec3(c.x, box.maxY, c.z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), hx, hz, fill));
        rects.add(new WorldMeshRenderer.PlaneRect(new Vec3(c.x, c.y, box.minZ), new Vec3(1, 0, 0), new Vec3(0, 1, 0), hx, hy, fill));
        rects.add(new WorldMeshRenderer.PlaneRect(new Vec3(c.x, c.y, box.maxZ), new Vec3(1, 0, 0), new Vec3(0, 1, 0), hx, hy, fill));
        Vec3 p000 = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 p001 = new Vec3(box.minX, box.minY, box.maxZ);
        Vec3 p010 = new Vec3(box.minX, box.maxY, box.minZ);
        Vec3 p011 = new Vec3(box.minX, box.maxY, box.maxZ);
        Vec3 p100 = new Vec3(box.maxX, box.minY, box.minZ);
        Vec3 p101 = new Vec3(box.maxX, box.minY, box.maxZ);
        Vec3 p110 = new Vec3(box.maxX, box.maxY, box.minZ);
        Vec3 p111 = new Vec3(box.maxX, box.maxY, box.maxZ);
        lines.add(new WorldMeshRenderer.Line(p000, p001, edge));
        lines.add(new WorldMeshRenderer.Line(p000, p010, edge));
        lines.add(new WorldMeshRenderer.Line(p000, p100, edge));
        lines.add(new WorldMeshRenderer.Line(p001, p011, edge));
        lines.add(new WorldMeshRenderer.Line(p001, p101, edge));
        lines.add(new WorldMeshRenderer.Line(p010, p011, edge));
        lines.add(new WorldMeshRenderer.Line(p010, p110, edge));
        lines.add(new WorldMeshRenderer.Line(p100, p101, edge));
        lines.add(new WorldMeshRenderer.Line(p100, p110, edge));
        lines.add(new WorldMeshRenderer.Line(p101, p111, edge));
        lines.add(new WorldMeshRenderer.Line(p011, p111, edge));
        lines.add(new WorldMeshRenderer.Line(p110, p111, edge));
    }

    public record PlaceData(BlockPos blockPos, Direction face, double damage, PlaceResult placeResult, Entity entity) {
    }

    @Getter
    @AllArgsConstructor
    public static class VisualData {
        private final BlockPos blockPos;
        private final long createdAt;
        private float r;
        private float g;
        private float b;
    }

    public enum PlaceResult {
        PLACE_OBSIDIAN,
        PLACE_CRYSTAL,
        EXPLOSION,
        BREAK,
        AWAITING
    }

    public enum ReturnResult {
        ITEM,
        PLAYER,
        NONE
    }
}