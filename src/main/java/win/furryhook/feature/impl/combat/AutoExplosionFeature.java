package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.packet.PacketSendEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.visual.CrystalOptimizerFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.mixin.accessor.MultiPlayerGameModeAccessor;
import win.furryhook.utils.FriendManager;

/**
 * AutoExplosion — комбо при ручной установке обсидиана + CrystalSpam для спама кристаллами.
 * Спам ищет лучшую позицию каждый тик (урон по врагу макс, по себе в капе), ставит и сразу
 * взрывает. CrystalOptimizer при спаме включается автоматически чтобы не просаживать FPS.
 */
public final class AutoExplosionFeature extends Feature {

    private final BooleanSetting safeYourSelf = register(new BooleanSetting("Не взрывать себя", false));
    private final BooleanSetting safeFriend = register(new BooleanSetting("Не взрывать друзей", true));
    private final NumberSetting radius = register(new NumberSetting("Радиус", 5.0, 1.0, 10.0, 0.1, ""));
    private final BooleanSetting comboRotate = register(new BooleanSetting("Ротация", true));

    private final BooleanSetting crystalSpam = register(new BooleanSetting("Спам кристаллами", false));
    private final NumberSetting spamRadius = register(new NumberSetting("Спам радиус", 5.0, 1.0, 7.0, 0.5, "").visibleWhen(crystalSpam::getValue));
    private final NumberSetting spamDelay = register(new NumberSetting("Спам задержка", 1.0, 0.0, 10.0, 1.0, " ticks").visibleWhen(crystalSpam::getValue));
    private final NumberSetting spamMin = register(new NumberSetting("Спам мин урон", 4.0, 0.0, 36.0, 0.5, "").visibleWhen(crystalSpam::getValue));
    private final NumberSetting spamSelf = register(new NumberSetting("Спам макс себе", 10.0, 0.0, 36.0, 0.5, "").visibleWhen(crystalSpam::getValue));
    private final BooleanSetting spamRotate = register(new BooleanSetting("Спам ротация", true).visibleWhen(crystalSpam::getValue));
    private final BooleanSetting spamOptimize = register(new BooleanSetting("Оптимизация рендера", true).visibleWhen(crystalSpam::getValue));

    private static final double DIAMETER = 12.0D;

    private BlockPos pos;
    private int ticksAction;
    private boolean crystalPlaced;
    private int hotSlot;
    private int spamCooldown;

    /** Клиент-слот, куда вернуться после ставки кристалла; -1 — возвращать нечего. */
    private int slotToRestore = -1;
    /** Тиков до возврата слота после ставки кристалла. */
    private int restoreTicks = 0;

    // Пошаговая ставка против Грима: свитч на кристалл и useItemOn в одном тике Грим флагает
    // (сервер не успевает применить смену слота до клика). placementStage: 0 — idle,
    // 1 — слот переключён, ждём placementTicks тиков, затем реальный useItemOn.
    private int placementStage = 0;
    private int placementTicks = 0;
    private BlockHitResult pendingHit;

    // Пометка "свой" рей-плейс кристалла: пока true, onPacketSend игнорит
    // ServerboundUseItemOnPacket, чтобы пакетная ставка не спуталась с ручной установкой обсидиана.
    private boolean packetPlace;

    public AutoExplosionFeature() {
        super("AutoExplosion", "Автоматически ставит и взрывает кристалл", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        resetCombo();
        spamCooldown = 0;
        packetPlace = false;
        placementStage = 0;
        placementTicks = 0;
        pendingHit = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && this.slotToRestore != -1) {
            mc.player.getInventory().setSelectedSlot(this.slotToRestore);
            if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                acc.invokerEnsureHasSentCarriedItem();
            }
            this.slotToRestore = -1;
            this.restoreTicks = 0;
        }
        RotationContext.disengage();
    }

    /**
     * Ловим когда ТЫ ставишь обсидиан через PacketSendEvent
     */
    @EventTarget
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPhase() != PacketSendEvent.Phase.PRE) return;
        if (this.packetPlace) return;
        if (!(event.getPacket() instanceof ServerboundUseItemOnPacket packet)) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // Проверяем что ТЫ ставишь обсидиан (из главной или оффхенда)
        Item mainItem = player.getMainHandItem().getItem();
        Item offItem = player.getOffhandItem().getItem();
        if (mainItem != Item.byBlock(Blocks.OBSIDIAN)
                && mainItem != Item.byBlock(Blocks.CRYING_OBSIDIAN)
                && offItem != Item.byBlock(Blocks.OBSIDIAN)
                && offItem != Item.byBlock(Blocks.CRYING_OBSIDIAN)) {
            return;
        }

        if (pos != null) return; // Уже обрабатываем

        hotSlot = getSlotForCrystal(player);
        if (hotSlot == -1) return; // Нет кристалла

        // Получаем позицию где будет обсидиан
        BlockHitResult hitResult = packet.getHitResult();
        BlockPos clickedPos = hitResult.getBlockPos();
        Direction direction = hitResult.getDirection();
        pos = clickedPos.relative(direction);

        ticksAction = 0;
    }

    @EventTarget
    public void onUpdate(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // Возврат слота после ставки кристалла: форсим пакет SetCarriedItem, иначе сервер
        // (и Грим) так и считает, что в руке кристалл — "слот застрял".
        if (this.restoreTicks > 0) {
            this.restoreTicks--;
            if (this.restoreTicks == 0 && this.slotToRestore != -1) {
                player.getInventory().setSelectedSlot(this.slotToRestore);
                if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                    acc.invokerEnsureHasSentCarriedItem();
                }
                this.slotToRestore = -1;
            }
        }

        // Фаза ставки: слот уже переключён на кристалл, выждали один тик (Грим применил
        // смену слота) — теперь реальный клик. Возврат слота уходит в restoreTicks.
        if (this.placementStage == 1) {
            this.placementTicks--;
            if (this.placementTicks <= 0 && this.pendingHit != null && mc.gameMode != null) {
                this.placementStage = 0;
                BlockHitResult hit = this.pendingHit;
                this.pendingHit = null;
                this.packetPlace = true;
                try {
                    mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                    player.swing(InteractionHand.MAIN_HAND);
                } finally {
                    this.packetPlace = false;
                }
                this.restoreTicks = 2;
            }
        }

        // --- комбо после ручной установки обсидиана ---
        if (pos != null) {
            if (!crystalPlaced) {
                // Фаза 1: ждём, пока клиент увидит поставленный нами обсидиан (эхо сервера
                // или локальная предикция). Кристалл на пустоту не ставится — сервер режектит.
                var block = mc.level.getBlockState(pos).getBlock();
                boolean obsidianOk = block == Blocks.OBSIDIAN
                        || block == Blocks.BEDROCK
                        || block == Blocks.CRYING_OBSIDIAN;
                if (!obsidianOk) {
                    ticksAction++;
                    if (ticksAction > 20) {
                        resetCombo();
                    }
                    return;
                }
                crystalPlaced = true;
                ticksAction = 0;
                // Сначала дожидаемся возврата слота от прошлой ставки спама, иначе
                // slotToRestore зацепит уже переключённый на кристалл слот.
                if (this.placementStage != 0 || this.pendingHit != null
                        || this.restoreTicks > 0 || this.slotToRestore != -1) {
                    return;
                }
                // Ставим кристалл честным клиентским слот-свитчем: клиент реально берёт
                // кристалл в руку (видно в HUD, сервер согласен), свитч и клик разнесены
                // на разные тики (Грим), клик через gameMode (предикция), и через 2 тика
                // слот возвращается.
                if (comboRotate.getValue()) {
                    snapTo(player, Vec3.atCenterOf(pos.above()));
                }
                // Кликаем верхнюю грань самого обсидиана: blockPos == pos, иначе сервер,
                // сверив кликнутый блок, режектит ставку (старый баг с pos.below()).
                Vec3 hitLoc = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                scheduleCrystalPlace(player, new BlockHitResult(hitLoc, Direction.UP, pos, false));
            }
            // Фаза 2: кристалл появился клиентски — бьём и режем комбо. Если эхо сервера ещё не
            // дошло, ретраим следующие тики до лимита.
            if (tryAttackCrystal(player, pos.above())) {
                resetCombo();
                return;
            }
            ticksAction++;
            if (ticksAction > 30) {
                resetCombo();
            }
            return;
        }

        // --- спам кристаллами ---
        if (!crystalSpam.getValue()) return;
        if (spamCooldown > 0) {
            spamCooldown--;
            return;
        }
        spamCooldown = spamDelay.getValue().intValue();
        doSpam(player, mc.level);
    }

    // ================= спам =================

    /**
     * Сброс комбо: позиция, фаза и ротация.
     */
    private void resetCombo() {
        pos = null;
        crystalPlaced = false;
        ticksAction = 0;
        hotSlot = 0;
        if (comboRotate.getValue()) {
            // После взрыва кристалла ротация сбрасывается мгновенно, а не плавным lerp.
            RotationContext.clear();
        }
    }

    private void doSpam(LocalPlayer player, ClientLevel level) {
        Minecraft client = Minecraft.getInstance();
        if (player.isUsingItem() || client.gameMode == null) return;

        // Слот ещё не вернулся после прошлой ставки, либо ставка в процессе (свитч сделали,
        // клик будет в следующий тик) — ждём, чтобы не налезть свитчем на restore/фазу
        // (слот "перевернётся" не туда).
        if (this.placementStage != 0 || this.pendingHit != null
                || this.restoreTicks > 0 || this.slotToRestore != -1) {
            if (this.spamCooldown <= 0) this.spamCooldown = this.spamDelay.getValue().intValue();
            return;
        }

        Player target = findTarget(player, level);
        if (target == null) {
            // Цели нет — нельзя оставлять ротацию висеть в точке последнего спама (Грим
            // кикает за фиксированный взгляд в воздух).
            if (spamRotate.getValue()) RotationContext.clear();
            return;
        }

        // 1) взрываем лучший уже стоящий кристалл
        boolean attackedCrystal = false;
        EndCrystal bestCrystal = null;
        double bestCrystalScore = 0;
        for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, player.getBoundingBox().inflate(7))) {
            if (!crystal.isAlive()) continue;
            if (player.getEyePosition().distanceTo(crystal.position().add(0, 0.5, 0)) > 5.5D) continue;
            double dmg = explosionDamage(crystal.position().add(0, 0.5, 0), target, level, player);
            if (dmg < spamMin.getValue()) continue;
            double self = explosionDamage(crystal.position().add(0, 0.5, 0), player, level, player);
            if (self > spamSelf.getValue()) continue;
            if (dmg > bestCrystalScore) {
                bestCrystalScore = dmg;
                bestCrystal = crystal;
            }
        }
        if (bestCrystal != null && canExplode(player, bestCrystal.blockPosition())) {
            if (spamRotate.getValue()) {
                snapTo(player, bestCrystal.position().add(0, 0.5, 0));
            }
            client.gameMode.attack(player, bestCrystal);
            player.swing(InteractionHand.MAIN_HAND);
            enableOptimizer();
            attackedCrystal = true;
        }

        // 2) ставим новый кристалл на лучшую базу
        BlockPos origin = player.blockPosition();
        int r = (int) Math.ceil(spamRadius.getValue()) + 1;
        Vec3 eye = player.getEyePosition();

        BlockPos bestBase = null;
        Direction bestFace = Direction.UP;
        double bestDmg = 0;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos base = origin.offset(dx, dy, dz);
                    double spamR = spamRadius.getValue();
                    if (dx * dx + dy * dy + dz * dz > spamR * spamR + 2) continue;
                    var block = level.getBlockState(base).getBlock();
                    if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) continue;
                    BlockPos place = base.above();
                    if (!level.getBlockState(place).isAir()) continue;
                    if (!level.getBlockState(place.above()).isAir()) continue;
                    Vec3 center = Vec3.atCenterOf(place);
                    if (eye.distanceTo(center) > 5.5D) continue;

                    // занято сущностью
                    AABB crystalBox = new AABB(place.getX(), place.getY(), place.getZ(), place.getX() + 1, place.getY() + 2, place.getZ() + 1);
                    boolean blocked = false;
                    for (Entity e : level.getEntities(null, crystalBox)) {
                        if (e == player) continue;
                        if (e.isAlive() && !e.isSpectator()) {
                            blocked = true;
                            break;
                        }
                    }
                    if (blocked) continue;

                    Direction face = visibleFace(level, player, eye, base);
                    if (face == null) continue;

                    double dmg = explosionDamage(center, target, level, player);
                    if (dmg < spamMin.getValue() || dmg <= bestDmg) continue;
                    double self = explosionDamage(center, player, level, player);
                    if (self > spamSelf.getValue()) continue;
                    if (safeFriend.getValue() && isFriendNearby(place)) continue;

                    bestDmg = dmg;
                    bestBase = base;
                    bestFace = face;
                }
            }
        }

        if (bestBase == null) {
            if (attackedCrystal && spamRotate.getValue()) {
                RotationContext.clear();
            }
            return;
        }

        int slot = getSlotForCrystal(player);
        if (slot == -1) {
            if (attackedCrystal && spamRotate.getValue()) {
                RotationContext.clear();
            }
            return;
        }

        Vec3 center = Vec3.atCenterOf(bestBase.above());
        if (spamRotate.getValue()) {
            snapTo(player, center);
        }
        Vec3 facePoint = new Vec3(
                bestBase.getX() + 0.5 + bestFace.getStepX() * 0.5,
                bestBase.getY() + 0.5 + bestFace.getStepY() * 0.5,
                bestBase.getZ() + 0.5 + bestFace.getStepZ() * 0.5);
        scheduleCrystalPlace(player, new BlockHitResult(facePoint, bestFace, bestBase, false));
        enableOptimizer();
    }

    /**
     * Сайлент-ротация взгляда на точку: сервер видит поворот, свой взгляд не дёргается
     * (RotationContext держит камеру на freeLook). Если кристалл в руке (ставишь сам) —
     * ротации нет вообще.
     */
    private void snapTo(LocalPlayer player, Vec3 target) {
        if (isHoldingCrystal(player)) return;
        Vec3 eye = player.getEyePosition();
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
        RotationContext.setRotation(yaw, pitch);
    }

    private boolean isHoldingCrystal(LocalPlayer player) {
        return player.getMainHandItem().getItem() == Items.END_CRYSTAL
                || player.getOffhandItem().getItem() == Items.END_CRYSTAL;
    }

    private void enableOptimizer() {
        if (!spamOptimize.getValue()) return;
        try {
            CrystalOptimizerFeature opt = FeatureManager.INSTANCE.getFeature(CrystalOptimizerFeature.class);
            if (opt != null && !opt.isEnabled()) opt.setEnabled(true);
        } catch (Throwable ignored) {
        }
    }

    private Player findTarget(LocalPlayer self, ClientLevel level) {
        Player best = null;
        double bestDist = 8.0D * 8.0D;
        for (Player p : level.players()) {
            if (p == self || !p.isAlive() || p.isRemoved()) continue;
            if (p.isSpectator() || p.isCreative()) continue;
            if (FriendManager.INSTANCE.isFriend(p.getGameProfile().name())) continue;
            double d = self.distanceToSqr(p);
            if (d > bestDist) continue;
            bestDist = d;
            best = p;
        }
        return best;
    }

    private Direction visibleFace(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos base) {
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            Vec3 point = new Vec3(
                    base.getX() + 0.5 + dir.getStepX() * 0.5,
                    base.getY() + 0.5 + dir.getStepY() * 0.5,
                    base.getZ() + 0.5 + dir.getStepZ() * 0.5);
            float[] rot = snapRotate(eye, point);
            if (!raycastBoxBlock(rot, base, player)) continue;
            double d = eye.distanceToSqr(point);
            if (d < bestDist) {
                bestDist = d;
                best = dir;
            }
        }
        return best;
    }

    private float[] snapRotate(Vec3 eye, Vec3 point) {
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }

    private boolean raycastBoxBlock(float[] rot, BlockPos targetPos, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(rot[1], rot[0]);
        Vec3 end = eye.add(dir.scale(4.5D));
        return new AABB(targetPos).clip(eye, end).isPresent();
    }

    // ================= урон =================

    private double explosionDamage(Vec3 source, LivingEntity target, ClientLevel level, LocalPlayer self) {
        if (target.position().distanceTo(source) > DIAMETER) return 0;
        double exposure = seenPercent(source, target.getBoundingBox(), level, self);
        if (exposure <= 0) return 0;
        double impact = (1.0D - target.position().distanceTo(source) / DIAMETER) * exposure;
        if (impact <= 0) return 0;
        float raw = (float) ((impact * impact + impact) / 2.0D * 7.0D * DIAMETER + 1.0D);
        float armor = (float) Math.floor(target.getArmorValue());
        float toughness = 0;
        try {
            toughness = (float) target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        } catch (Throwable ignored) {
        }
        float i = 2.0F + toughness / 4.0F;
        float j = Mth.clamp(armor - raw / i, armor * 0.2F, 20.0F);
        raw = raw * (1.0F - j / 25.0F);
        var res = target.getEffect(MobEffects.RESISTANCE);
        if (res != null) raw *= 1.0F - 0.2F * (res.getAmplifier() + 1);
        return Math.max(raw, 0);
    }

    private double seenPercent(Vec3 source, AABB box, ClientLevel level, LocalPlayer self) {
        double dx = 1.0D / ((box.maxX - box.minX) * 2.0D + 1.0D);
        double dy = 1.0D / ((box.maxY - box.minY) * 2.0D + 1.0D);
        double dz = 1.0D / ((box.maxZ - box.minZ) * 2.0D + 1.0D);
        if (dx < 0 || dy < 0 || dz < 0) return 0;
        double xOff = (1.0D - Math.floor(1.0D / dx) * dx) * 0.5D;
        double zOff = (1.0D - Math.floor(1.0D / dz) * dz) * 0.5D;
        int hit = 0, total = 0;
        for (double x = 0; x <= 1.0D; x += dx) {
            for (double y = 0; y <= 1.0D; y += dy) {
                for (double z = 0; z <= 1.0D; z += dz) {
                    Vec3 sample = new Vec3(
                            Mth.lerp(x, box.minX, box.maxX) + xOff,
                            Mth.lerp(y, box.minY, box.maxY),
                            Mth.lerp(z, box.minZ, box.maxZ) + zOff);
                    HitResult r = level.clip(new ClipContext(sample, source, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
                    if (r.getType() == HitResult.Type.MISS) hit++;
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    /**
     * Честная клиентская ставка кристалла, разнесённая на тики против Грима:
     * переключаем реальный слот на кристалл и форсим пакет SetCarriedItem —
     * сервер узнаёт слот до клика. Сам клик (useItemOn) уходит в placementStage
     * на следующем тике, возврат слота — через restoreTicks. Так клиент и сервер
     * согласованы, HUD показывает кристалл, и Грим не видит ни "гост-предмета"
     * ("в руке обсидиан, а ставится кристалл"), ни same-tick свитч+клик.
     */
    private void scheduleCrystalPlace(LocalPlayer player, BlockHitResult hitResult) {
        if (this.placementStage != 0 || this.pendingHit != null) return;
        Minecraft mc = Minecraft.getInstance();
        int slot = getSlotForCrystal(player);
        if (slot == -1 || mc.gameMode == null) return;

        if (this.slotToRestore == -1) {
            this.slotToRestore = player.getInventory().getSelectedSlot();
        }

        player.getInventory().setSelectedSlot(slot);
        if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
            acc.invokerEnsureHasSentCarriedItem();
        }

        this.pendingHit = hitResult;
        this.placementStage = 1;
        this.placementTicks = 1;
    }

    /**
     * Бьёт кристалл на позиции, если тот уже виден клиентски (эхо сервера дошло).
     * Возвращает true, когда кристалл найден и обработан, false — если ещё не появился
     * (комбо ретраит следующие тики).
     */
    private boolean tryAttackCrystal(LocalPlayer player, BlockPos aboveObsidian) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameMode == null) return false;

        for (EndCrystal crystal : mc.level.getEntitiesOfClass(EndCrystal.class, player.getBoundingBox().inflate(6))) {
            if (crystal.blockPosition().equals(aboveObsidian)) {
                if (canExplode(player, aboveObsidian)) {
                    if (comboRotate.getValue()) {
                        snapTo(player, crystal.position().add(0, 0.5, 0));
                    }
                    mc.gameMode.attack(player, crystal);
                    player.swing(InteractionHand.MAIN_HAND);
                }
                return true;
            }
        }
        return false;
    }

    private boolean canExplode(LocalPlayer player, BlockPos crystalPos) {
        if (safeYourSelf.getValue()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                // Реальная расчётная порча по себе от этого кристалла, а не тупая проверка по Y.
                double selfDamage = explosionDamage(
                        Vec3.atBottomCenterOf(crystalPos).add(0, 0.5, 0),
                        player, mc.level, player);
                if (selfDamage > 2.0D) {
                    return false;
                }
            }
        }

        return !safeFriend.getValue() || !isFriendNearby(crystalPos);
    }

    private boolean isFriendNearby(BlockPos position) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        double explosionRadius = radius.getValue();
        AABB area = new AABB(
                position.getX() - explosionRadius, position.getY() - explosionRadius, position.getZ() - explosionRadius,
                position.getX() + explosionRadius, position.getY() + explosionRadius, position.getZ() + explosionRadius
        );

        for (Entity entity : mc.level.getEntitiesOfClass(Entity.class, area)) {
            if (entity instanceof Player player) {
                if (FriendManager.INSTANCE.isFriend(player.getName().getString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getSlotForCrystal(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() == Items.END_CRYSTAL) {
                return i;
            }
        }
        return -1;
    }
}
