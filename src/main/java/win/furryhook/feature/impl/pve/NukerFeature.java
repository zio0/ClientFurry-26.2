package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.mining.MiningInventory;
import win.furryhook.pve.mining.MiningParsers;
import win.furryhook.pve.mining.MiningSessionSnapshot;
import win.furryhook.pve.mining.MiningTargetSelector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class NukerFeature extends PveFeature {
    private static final double MAX_REACH_SQUARED = 25.0D;
    private static final int INSTANT_LIMIT = 8;

    public final NumberSetting radiusXz = register(new NumberSetting(
            "Radius XZ", 3.0D, 1.0D, 6.0D, 1.0D, " blocks"
    ));
    public final NumberSetting radiusY = register(new NumberSetting(
            "Radius Y", 3.0D, 1.0D, 6.0D, 1.0D, " blocks"
    ));
    public final ModeSetting workMode = register(new ModeSetting(
            "Work Mode", "Everywhere", "Everywhere", "Only Mine"
    ));
    public final TextSetting mineRegion = register(new TextSetting(
            "Mine Region", "", 96
    ));
    public final ModeSetting diggingMode = register(new ModeSetting(
            "Digging Mode", "Everyone", "Everyone", "Ore Priority", "Only Ore"
    ));
    public final NumberSetting yawSpeed = register(new NumberSetting(
            "Yaw Speed", 180.0D, 1.0D, 180.0D, 1.0D, " deg/tick"
    ).visibleWhen(PveManagerFeature.INSTANCE.rotate::getValue));
    public final NumberSetting pitchSpeed = register(new NumberSetting(
            "Pitch Speed", 180.0D, 1.0D, 180.0D, 1.0D, " deg/tick"
    ).visibleWhen(PveManagerFeature.INSTANCE.rotate::getValue));
    public final BooleanSetting throughWalls = register(new BooleanSetting(
            "Through Walls", false
    ));
    public final BooleanSetting mineNeighbor = register(new BooleanSetting(
            "Mine Neighbor", false
    ));
    public final BooleanSetting mineDown = register(new BooleanSetting(
            "Mine Down", false
    ));
    public final BooleanSetting instant = register(new BooleanSetting(
            "Instant", false
    ));

    private final MiningSessionSnapshot snapshot = new MiningSessionSnapshot();
    private BlockPos currentTarget;

    public NukerFeature() {
        super(
                "Nuker",
                "Breaks nearby blocks using safe target filtering",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE,
                AutomationResource.ROTATION
        );
    }

    @Override
    protected void onPveEnable() {
        this.currentTarget = null;
        this.snapshot.capture(Minecraft.getInstance().player);
    }

    @Override
    protected void onPveDisable() {
        cleanup();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        cleanup();
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = event.getPlayer();
        ClientLevel level = client.level;
        if (player == null || level == null || client.gameMode == null
                || !player.isAlive() || player.isSpectator() || client.gui.screen() != null) {
            cancelBreaking(client);
            return;
        }
        this.snapshot.capture(player);

        List<MiningTargetSelector.Candidate<BlockPos>> candidates =
                collectCandidates(level, player);
        Optional<MiningTargetSelector.Candidate<BlockPos>> selected =
                MiningTargetSelector.select(
                        candidates,
                        resolvedDiggingMode(),
                        this.throughWalls.getValue()
                );
        if (selected.isEmpty()) {
            cancelBreaking(client);
            return;
        }

        MiningTargetSelector.Candidate<BlockPos> target = selected.get();
        if (this.mineNeighbor.getValue()) {
            List<MiningTargetSelector.Candidate<BlockPos>> neighbors =
                    collectNeighbors(level, player, target.value());
            target = MiningTargetSelector.easierNeighbor(
                    target,
                    neighbors,
                    this.throughWalls.getValue()
            ).orElse(target);
        }

        if (this.instant.getValue()
                && instantBreak(client, level, player, candidates)) {
            this.currentTarget = target.value();
            return;
        }

        breakTarget(client, level, player, target.value());
    }

    public BlockPos getCurrentTarget() {
        return this.currentTarget;
    }

    private List<MiningTargetSelector.Candidate<BlockPos>> collectCandidates(
            ClientLevel level,
            LocalPlayer player
    ) {
        int horizontal = this.radiusXz.getValue().intValue();
        int vertical = this.radiusY.getValue().intValue();
        BlockPos origin = player.blockPosition();
        int minimumY = this.mineDown.getValue() ? origin.getY() - vertical : origin.getY();
        List<MiningTargetSelector.Candidate<BlockPos>> candidates = new ArrayList<>();
        for (int x = origin.getX() - horizontal; x <= origin.getX() + horizontal; x++) {
            for (int y = minimumY; y <= origin.getY() + vertical; y++) {
                for (int z = origin.getZ() - horizontal; z <= origin.getZ() + horizontal; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    candidate(level, player, origin, position).ifPresent(candidates::add);
                }
            }
        }
        return candidates;
    }

    private List<MiningTargetSelector.Candidate<BlockPos>> collectNeighbors(
            ClientLevel level,
            LocalPlayer player,
            BlockPos position
    ) {
        List<MiningTargetSelector.Candidate<BlockPos>> neighbors = new ArrayList<>();
        BlockPos origin = player.blockPosition();
        for (Direction direction : Direction.values()) {
            candidate(level, player, origin, position.relative(direction))
                    .filter(candidate -> !this.diggingMode.is("Only Ore")
                            || candidate.ore())
                    .ifPresent(neighbors::add);
        }
        return neighbors;
    }

    private Optional<MiningTargetSelector.Candidate<BlockPos>> candidate(
            ClientLevel level,
            LocalPlayer player,
            BlockPos origin,
            BlockPos position
    ) {
        if (!this.mineDown.getValue() && position.getY() < origin.getY()) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(position);
        boolean inWorkArea = isInWorkArea(position);
        boolean safe = isSafe(level, position, state)
                && distanceToBlockSquared(player.getEyePosition(), position) <= MAX_REACH_SQUARED;
        if (!safe) {
            return Optional.empty();
        }
        boolean visible = isVisible(level, player, position);
        return Optional.of(new MiningTargetSelector.Candidate<>(
                position.immutable(),
                true,
                MiningInventory.isOre(state.getBlock()),
                visible,
                inWorkArea,
                position.getY() - origin.getY(),
                player.distanceToSqr(Vec3.atCenterOf(position)),
                state.getDestroySpeed(level, position)
        ));
    }

    private boolean isSafe(ClientLevel level, BlockPos position, BlockState state) {
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !state.hasBlockEntity()
                && MiningInventory.isSafeBreakTarget(state.getBlock())
                && state.getDestroySpeed(level, position) >= 0.0F
                && !state.getCollisionShape(level, position).isEmpty();
    }

    private boolean isInWorkArea(BlockPos position) {
        if (!this.workMode.is("Only Mine")) {
            return true;
        }
        return MiningParsers.region(this.mineRegion.getValue())
                .map(region -> region.contains(position))
                .orElse(false);
    }

    private boolean isVisible(ClientLevel level, LocalPlayer player, BlockPos position) {
        BlockHitResult hit = level.clip(new ClipContext(
                player.getEyePosition(),
                Vec3.atCenterOf(position),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(position);
    }

    private void breakTarget(
            Minecraft client,
            ClientLevel level,
            LocalPlayer player,
            BlockPos target
    ) {
        if (PveManagerFeature.INSTANCE.rotate.getValue()) {
            rotateToward(player, target);
        }
        if (!target.equals(this.currentTarget)) {
            cancelBreaking(client);
            this.currentTarget = target.immutable();
            client.gameMode.startDestroyBlock(target, hitDirection(level, player, target));
        }
        client.gameMode.continueDestroyBlock(target, hitDirection(level, player, target));
        player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean instantBreak(
            Minecraft client,
            ClientLevel level,
            LocalPlayer player,
            List<MiningTargetSelector.Candidate<BlockPos>> candidates
    ) {
        List<MiningTargetSelector.Candidate<BlockPos>> instantTargets = candidates.stream()
                .filter(MiningTargetSelector.Candidate::safe)
                .filter(MiningTargetSelector.Candidate::inWorkArea)
                .filter(candidate -> this.throughWalls.getValue() || candidate.visible())
                .filter(candidate -> !this.diggingMode.is("Only Ore") || candidate.ore())
                .filter(candidate -> player.isCreative()
                        || level.getBlockState(candidate.value()).getDestroyProgress(
                        player,
                        level,
                        candidate.value()
                ) >= 1.0F)
                .sorted(Comparator
                        .<MiningTargetSelector.Candidate<BlockPos>>comparingInt(candidate ->
                                this.diggingMode.is("Ore Priority") && candidate.ore()
                                        ? 0
                                        : 1)
                        .thenComparingDouble(
                                MiningTargetSelector.Candidate::distanceSquared
                        ))
                .limit(INSTANT_LIMIT)
                .toList();
        for (MiningTargetSelector.Candidate<BlockPos> candidate : instantTargets) {
            client.gameMode.startDestroyBlock(candidate.value(), Direction.UP);
            player.swing(InteractionHand.MAIN_HAND);
        }
        return !instantTargets.isEmpty();
    }

    private Direction hitDirection(ClientLevel level, LocalPlayer player, BlockPos position) {
        BlockHitResult hit = level.clip(new ClipContext(
                player.getEyePosition(),
                Vec3.atCenterOf(position),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position)
                ? hit.getDirection()
                : Direction.UP;
    }

    private void rotateToward(LocalPlayer player, BlockPos position) {
        Vec3 difference = Vec3.atCenterOf(position).subtract(player.getEyePosition());
        double horizontal = Math.sqrt(difference.x * difference.x + difference.z * difference.z);
        float targetYaw = (float) Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(difference.y, horizontal));
        float yawDelta = Mth.wrapDegrees(targetYaw - player.getYRot());
        float pitchDelta = Mth.wrapDegrees(targetPitch - player.getXRot());
        player.setYRot(player.getYRot() + Mth.clamp(
                yawDelta,
                -this.yawSpeed.getValue().floatValue(),
                this.yawSpeed.getValue().floatValue()
        ));
        player.setXRot(player.getXRot() + Mth.clamp(
                pitchDelta,
                -this.pitchSpeed.getValue().floatValue(),
                this.pitchSpeed.getValue().floatValue()
        ));
    }

    private MiningTargetSelector.DiggingMode resolvedDiggingMode() {
        if (this.diggingMode.is("Only Ore")) {
            return MiningTargetSelector.DiggingMode.ONLY_ORE;
        }
        if (this.diggingMode.is("Ore Priority")) {
            return MiningTargetSelector.DiggingMode.ORE_PRIORITY;
        }
        return MiningTargetSelector.DiggingMode.EVERYONE;
    }

    private static double distanceToBlockSquared(Vec3 point, BlockPos position) {
        double x = point.x - Mth.clamp(point.x, position.getX(), position.getX() + 1.0D);
        double y = point.y - Mth.clamp(point.y, position.getY(), position.getY() + 1.0D);
        double z = point.z - Mth.clamp(point.z, position.getZ(), position.getZ() + 1.0D);
        return x * x + y * y + z * z;
    }

    private void cancelBreaking(Minecraft client) {
        if (client != null && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        this.currentTarget = null;
    }

    private void cleanup() {
        Minecraft client = Minecraft.getInstance();
        cancelBreaking(client);
        this.snapshot.restore(client);
    }
}
