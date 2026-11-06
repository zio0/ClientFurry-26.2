package win.furryhook.feature.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.mixin.accessor.MultiPlayerGameModeAccessor;
import win.furryhook.utils.combat.PredictUtils;
import win.furryhook.utils.combat.TargetFilter;
import win.furryhook.utils.combat.TargetUtil;
import win.furryhook.utils.inventory.InventorySwap;
import win.furryhook.utils.inventory.InventoryUtil;

/**
 * AutoWeb — ставит паутину под ноги цели в предсказанную позицию, затем верхнюю паутину
 * на следующий тик. Ротация — через RotationContext, чтобы сервер видел поворот, а свой
 * взгляд не дёргался. При выключении ротация и слот сбрасываются.
 */
public final class AutoWebFeature extends Feature {

    private final BooleanSetting rotate = register(new BooleanSetting("Ротация", true));
    private final BooleanSetting doubleWeb = register(new BooleanSetting("Двойная паутина", true));
    private final BooleanSetting throughWalls = register(new BooleanSetting("Сквозь стены", true));
    private final BooleanSetting ignoreSelf = register(new BooleanSetting("Игнорировать себя", true));
    private final NumberSetting range = register(new NumberSetting("Радиус", 4.5, 1.0, 6.0, 0.5, ""));
    private final NumberSetting predictTicks = register(new NumberSetting("Прогноз", 2.0, 0.0, 6.0, 1.0, " ticks"));

    private static final double PLACE_REACH = 5.0D;
    private static final int HOTBAR_SIZE = 9;

    private int slotToRestore = -1;
    private int ticksToReturnSlot = -1;

    /** Верхняя паутина на след. тик после нижней (doubleWeb). */
    private BlockPos pendingUpperPos = null;
    /** Слот уже переключён на паутину, этот хит надо юзнуть следующим тиком (Грим). */
    private BlockHitResult pendingUseHit = null;

    /** Сколько тиков держим паутину в руке после последнего клика перед возвратом слота. */
    private static final int RESTORE_TICKS = 2;

    public AutoWebFeature() {
        super("AutoWeb", "Ставит паутину под ноги цели", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        this.pendingUpperPos = null;
        this.pendingUseHit = null;
        this.ticksToReturnSlot = -1;
        restoreSlot();
        RotationContext.disengage();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null) return;

        // Возвращаем слот после серии ставок
        if (this.ticksToReturnSlot > 0) {
            this.ticksToReturnSlot--;
            if (this.ticksToReturnSlot == 0) {
                restoreSlot();
            }
        }

        // Фаза ставки: слот уже переключён на паутину в прошлый тик (сервер применил смену
        // слота) — юзаем сейчас. Для двойной паутины хит верхней ставим после нижнего клика:
        // клиент уже предсказал блок нижней паутины, поэтому у верхней есть опора.
        if (this.pendingUseHit != null) {
            BlockHitResult hit = this.pendingUseHit;
            this.pendingUseHit = null;
            performWebUse(mc, player, hit);
            if (this.pendingUpperPos != null) {
                BlockPos upper = this.pendingUpperPos;
                this.pendingUpperPos = null;
                this.pendingUseHit = findPlaceHit(player, level, upper);
                if (this.pendingUseHit == null) {
                    this.ticksToReturnSlot = RESTORE_TICKS;
                }
            } else {
                this.ticksToReturnSlot = RESTORE_TICKS;
            }
            return;
        }

        // Идёт свап паутины из инвентаря в хотбар (глобальный InventorySwap) — ждём.
        if (InventorySwap.isBusy()) return;

        LivingEntity target = findTarget(player, level);
        if (target == null) {
            if (this.rotate.getValue()) RotationContext.disengage();
            return;
        }

        // Паутины в хотбаре нет — тянем из инвентаря в хотбар и ждём завершения свапа.
        int webSlot = getWebSlot(player);
        if (webSlot == -1) {
            int containerSlot = InventoryUtil.findInventorySlot(player, stack -> stack.getItem() == Items.COBWEB);
            if (containerSlot != -1) {
                InventorySwap.moveToHotbar(containerSlot, findSwapTargetSlot(player));
                return;
            }
            if (this.rotate.getValue()) RotationContext.disengage();
            return;
        }

        Vec3 predicted = PredictUtils.predict(target, target.position(), this.predictTicks.getValue().floatValue());
        BlockPos feet = new BlockPos((int) Math.floor(predicted.x), (int) Math.floor(predicted.y), (int) Math.floor(predicted.z));

        // Нижняя паутина: считаем хит и ротацию.
        BlockHitResult lowerHit = findPlaceHit(player, level, feet);
        if (lowerHit == null) {
            if (this.rotate.getValue()) RotationContext.disengage();
            return;
        }

        if (this.doubleWeb.getValue()) {
            this.pendingUpperPos = feet.above();
        }

        // Честный свитч: паутина реально в руке (HUD), сервер узнаёт через явный
        // SetCarriedItem. Оригинальный слот запоминаем ДО свитча, иначе паутина
        // навсегда останется в руке. Клик — следующим тиком (Грим).
        if (this.slotToRestore == -1) {
            this.slotToRestore = player.getInventory().getSelectedSlot();
        }
        player.getInventory().setSelectedSlot(webSlot);
        if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
            acc.invokerEnsureHasSentCarriedItem();
        }
        this.pendingUseHit = lowerHit;
    }

    private void restoreSlot() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player != null && this.slotToRestore >= 0 && this.slotToRestore <= 8) {
            player.getInventory().setSelectedSlot(this.slotToRestore);
            // Форсим отправку SetCarriedItem: иначе сервер так и не узнает о возврате слота,
            // пока что-нибудь другое не вызовет ensureHasSentCarriedItem.
            if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                acc.invokerEnsureHasSentCarriedItem();
            }
        }
        this.slotToRestore = -1;
    }

    private LivingEntity findTarget(LocalPlayer player, ClientLevel level) {
        TargetFilter filter = TargetFilter.builder()
                .targetsPlayers(true)
                .targetsFriends(false)
                .targetsNakedPlayers(true)
                .targetsInvisibles(false)
                .targetsVillagers(false)
                .targetsMonsters(false)
                .targetsAnimals(false)
                .targetsTeams(true)
                .distanceWeight(1.0D)
                .healthWeight(0.0D)
                .armorWeight(0.0D)
                .fovWeight(0.0D)
                .build();

        return TargetUtil.getBestTarget(player, level, this.range.getValue(), filter, entity -> true);
    }

    /**
     * Считает плейс-хит для позиции: видимость, опору, ротацию. Слота НЕ касается —
     * свитч делает onTick перед армом {@link #pendingUseHit}. Возвращает null, если
     * ставить некуда или опорный блок не виден (сквозные стены выключены).
     */
    private BlockHitResult findPlaceHit(LocalPlayer player, ClientLevel level, BlockPos placePos) {
        if (!canPlaceAt(player, level, placePos)) return null;

        PlaceData placeData = findPlaceData(player, level, placePos);
        if (placeData == null) return null;

        float[] rot = getRotations(player, placeData.hitVec());
        if (this.rotate.getValue()) {
            RotationContext.setRotation(rot[0], rot[1]);
        }

        // Проверяем видимость опорного блока (если не сквозь стены)
        if (!this.throughWalls.getValue() && !blockHit(player, level, rot, placeData.neighbourPos())) {
            return null;
        }

        return new BlockHitResult(placeData.hitVec(), placeData.side(), placeData.neighbourPos(), false);
    }

    /**
     * Реальный клик по уже проверенному хиту. Слот на паутину к этому моменту стоит
     * (переключён минимум один тик назад), поэтому useItemOn уходит с согласованным
     * held-slot'ом — Грим видит честную ставку.
     */
    private void performWebUse(Minecraft mc, LocalPlayer player, BlockHitResult hit) {
        if (mc.gameMode == null) return;
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean canPlaceAt(LocalPlayer player, ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced()) return false;

        // Не ставим паутину в себя
        if (player.getBoundingBox().inflate(0.05D).contains(Vec3.atCenterOf(pos))) {
            return false;
        }
        return true;
    }

    /**
     * Ищет соседний блок-опору с видимой гранью.
     */
    private PlaceData findPlaceData(LocalPlayer player, ClientLevel level, BlockPos placePos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbour = placePos.relative(side);
            Direction hitSide = side.getOpposite();

            BlockState neighbourState = level.getBlockState(neighbour);
            if (!isValidSupport(level, neighbour, neighbourState, hitSide)) continue;

            Vec3 faceCenter = new Vec3(
                    neighbour.getX() + 0.5 + hitSide.getStepX() * 0.5,
                    neighbour.getY() + 0.5 + hitSide.getStepY() * 0.5,
                    neighbour.getZ() + 0.5 + hitSide.getStepZ() * 0.5);
            return new PlaceData(neighbour, hitSide, faceCenter);
        }
        return null;
    }

    private boolean isValidSupport(ClientLevel level, BlockPos pos, BlockState state, Direction face) {
        if (state.isAir()) return false;
        if (state.getFluidState() != null && !state.getFluidState().isEmpty()) return false;

        Block block = state.getBlock();
        if (block == Blocks.COBWEB) return true;

        if (!state.isFaceSturdy(level, pos, face)) return false;

        if (block instanceof ButtonBlock) return false;
        if (block instanceof LeverBlock) return false;
        if (block instanceof TorchBlock) return false;
        if (block instanceof WallTorchBlock) return false;
        if (block instanceof RedstoneTorchBlock) return false;
        if (block instanceof FenceBlock) return false;
        if (block instanceof FenceGateBlock) return false;
        if (block instanceof DoorBlock) return false;
        if (block instanceof TrapDoorBlock) return false;
        if (block instanceof CarpetBlock) return false;
        if (block instanceof VineBlock) return false;
        if (block instanceof LadderBlock) return false;

        return true;
    }

    /**
     * Есть ли прямой луч от глаз до опорного блока.
     */
    private boolean blockHit(LocalPlayer player, ClientLevel level, float[] rot, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(rot[1], rot[0]);
        Vec3 end = eye.add(dir.scale(PLACE_REACH));

        HitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK
                && pos.equals(((BlockHitResult) hit).getBlockPos());
    }

    private float[] getRotations(LocalPlayer player, Vec3 point) {
        Vec3 eyes = player.getEyePosition();
        Vec3 delta = point.subtract(eyes);
        double distXZ = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, distXZ));
        if (pitch > 90.0F) pitch = 90.0F;
        if (pitch < -90.0F) pitch = -90.0F;
        return new float[]{yaw, pitch};
    }

    private int getWebSlot(LocalPlayer player) {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (player.getInventory().getItem(i).getItem() == Items.COBWEB) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Куда уложить паутину из инвентаря. Приоритет: пустой слот (не текущий), затем любой
     * слот без паутины, и только в крайнем случае — текущий выбранный слот.
     */
    private int findSwapTargetSlot(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (i != selected && player.getInventory().getItem(i).isEmpty()) return i;
        }
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (i != selected && player.getInventory().getItem(i).getItem() != Items.COBWEB) return i;
        }
        return selected;
    }

    private record PlaceData(BlockPos neighbourPos, Direction side, Vec3 hitVec) {
    }
}