package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.PveStateMachine;
import win.furryhook.pve.mining.BaseFinderFsm;
import win.furryhook.pve.mining.ContainerClusterScanner;
import win.furryhook.pve.mining.MiningInventory;
import win.furryhook.pve.mining.MiningParsers;
import win.furryhook.pve.mining.MiningServerAdapter;
import win.furryhook.pve.mining.MiningServerAdapters;
import win.furryhook.pve.mining.MiningSessionSnapshot;
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.navigation.Navigator;
import win.furryhook.utils.text.ChatUtil;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class BaseFinderFeature extends PveFeature {
    private static final String DEFAULT_TARGETS = String.join(",",
            "chest", "trapped_chest", "barrel", "shulker_box",
            "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
            "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box",
            "pink_shulker_box", "gray_shulker_box", "light_gray_shulker_box",
            "cyan_shulker_box", "purple_shulker_box", "blue_shulker_box",
            "brown_shulker_box", "green_shulker_box", "red_shulker_box",
            "black_shulker_box", "ender_chest", "hopper", "spawner",
            "trial_spawner"
    );
    private static final int TRANSFER_WAIT_TICKS = 80;
    private static final int RTP_WAIT_TICKS = 120;
    private static final int SCAN_BUDGET_PER_TICK = 2_048;
    private static final int STUCK_CHECK_TICKS = 160;

    public final TextSetting targets = register(new TextSetting(
            "Targets", DEFAULT_TARGETS, 512
    ));
    public final TextSetting route = register(new TextSetting(
            "Route", "", 1_024
    ));
    public final NumberSetting scanRadius = register(new NumberSetting(
            "Scan Radius", 24.0D, 12.0D, 48.0D, 1.0D, " blocks"
    ));
    public final NumberSetting minContainers = register(new NumberSetting(
            "Minimum Containers", 4.0D, 1.0D, 32.0D, 1.0D, ""
    ));
    public final ModeSetting heightMode = register(new ModeSetting(
            "Height Mode", "Fixed", "Fixed", "Smart"
    ));
    public final NumberSetting searchHeight = register(new NumberSetting(
            "Search Height", -32.0D, -60.0D, 30.0D, 1.0D, "Y"
    ));
    public final NumberSetting segmentLength = register(new NumberSetting(
            "Tunnel Segment", 48.0D, 12.0D, 128.0D, 4.0D, " blocks"
    ));
    public final BooleanSetting serverTransfer = register(new BooleanSetting(
            "Server Transfer", false
    ));
    public final ModeSetting rtpMode = register(new ModeSetting(
            "RTP Mode", "Big", "Small", "Big"
    ));
    public final NumberSetting transferInterval = register(new NumberSetting(
            "Transfer Interval", 20.0D, 2.0D, 120.0D, 1.0D, "min"
    ));

    private final Navigator navigator;
    private final MiningSessionSnapshot snapshot = new MiningSessionSnapshot();
    private final ContainerClusterScanner scanner = new ContainerClusterScanner();
    private final PveStateMachine<BaseFinderFsm.State> machine =
            new PveStateMachine<>(BaseFinderFsm.State.IDLE);

    private MiningServerAdapter adapter;
    private Set<Block> targetBlocks = Set.of();
    private List<BlockPos> routePoints = List.of();
    private BlockPos segmentGoal;
    private BlockPos foundPosition;
    private BlockPos lastProgressPosition;
    private BlockPos lastScanCenter;
    private long tick;
    private long nextScanTick;
    private long transferStartedTick;
    private long lastProgressTick;
    private long searchStartedTick;
    private float headingYaw;
    private int routeIndex;
    private int currentAnarchy;
    private int transferStage;
    private int bypassSide = 1;
    private int smartHeight = -60;
    private boolean stateActionStarted;
    private boolean usesServerTransfer;
    private boolean safetyPaused;
    private String pauseReason;
    private BaseFinderFsm.State resumeState = BaseFinderFsm.State.DESCENDING;

    public BaseFinderFeature() {
        this(BaritoneNavigator.INSTANCE);
    }

    BaseFinderFeature(Navigator navigator) {
        super(
                "BaseFinder",
                "Searches configurable routes for container clusters",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.BOT,
                AutomationResource.MOVEMENT,
                AutomationResource.ROTATION,
                AutomationResource.INVENTORY,
                AutomationResource.CHAT,
                AutomationResource.NAVIGATION
        );
        this.navigator = navigator;
    }

    @Override
    protected void onPveEnable() {
        Minecraft client = Minecraft.getInstance();
        this.tick = 0L;
        this.foundPosition = null;
        this.segmentGoal = null;
        this.routeIndex = 0;
        this.currentAnarchy = PveManagerFeature.INSTANCE.resolvedAnarchy();
        this.transferStage = 0;
        this.bypassSide = 1;
        this.smartHeight = -60;
        this.headingYaw = client.player == null ? 0.0F : client.player.getYRot();
        this.adapter = MiningServerAdapters.forProfile(
                PveManagerFeature.INSTANCE.resolveServerProfile(client)
        );
        this.targetBlocks = Set.copyOf(MiningInventory.resolveBlocks(
                MiningParsers.identifiers(this.targets.getValue())
        ));
        if (this.targetBlocks.isEmpty()) {
            throw new IllegalStateException("BaseFinder has no valid scan targets");
        }
        this.routePoints = MiningParsers.route(this.route.getValue()).stream()
                .map(MiningParsers.GridPoint::toBlockPos)
                .toList();
        this.usesServerTransfer = this.serverTransfer.getValue()
                && this.adapter.anarchyCommand(this.currentAnarchy).isPresent()
                && this.adapter.randomTeleportCommand(this.rtpMode.getValue()).isPresent();
        if (!this.navigator.isAvailable()) {
            throw new IllegalStateException("Baritone is unavailable");
        }
        this.snapshot.capture(client.player);
        this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                NavigationOptions.mining()
        ));
        this.scanner.reset();
        this.machine.reset(this.tick);
        transition(BaseFinderFsm.next(
                this.machine.state(),
                BaseFinderFsm.Signal.START,
                this.usesServerTransfer
        ));
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
    public void onTick(GameTickEvent event) {
        this.tick++;
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }
        this.snapshot.capture(player);

        String unsafe = safetyReason(client, player);
        if (unsafe != null) {
            pauseForSafety(unsafe);
            return;
        }
        if (this.machine.is(BaseFinderFsm.State.PAUSED)) {
            if (!this.safetyPaused) {
                return;
            }
            transition(BaseFinderFsm.resume(this.resumeState, this.usesServerTransfer));
        }

        if (shouldTransferAgain()) {
            transition(BaseFinderFsm.State.SERVER_TRANSFER);
        }

        if (this.machine.state() != BaseFinderFsm.State.IDLE
                && this.machine.state() != BaseFinderFsm.State.ERROR
                && this.machine.state() != BaseFinderFsm.State.FOUND
                && this.machine.state() != BaseFinderFsm.State.SERVER_TRANSFER) {
            tickScanner(client, player);
            if (this.machine.is(BaseFinderFsm.State.FOUND)) {
                return;
            }
        }

        switch (this.machine.state()) {
            case IDLE, FOUND, PAUSED, ERROR -> {
            }
            case SERVER_TRANSFER -> tickServerTransfer(player);
            case DESCENDING -> tickDescend(player);
            case SEARCHING -> tickSearch(player);
            case BYPASSING -> tickBypass(player);
        }
    }

    public BaseFinderFsm.State getState() {
        return this.machine.state();
    }

    public Optional<BlockPos> getFoundPosition() {
        return Optional.ofNullable(this.foundPosition);
    }

    private void tickServerTransfer(LocalPlayer player) {
        if (!this.stateActionStarted) {
            this.navigator.cancel();
            this.transferStage = 0;
            this.transferStartedTick = this.tick;
            Optional<String> command = this.adapter.anarchyCommand(
                    this.currentAnarchy
            );
            if (command.isEmpty()) {
                signal(BaseFinderFsm.Signal.TRANSFER_DONE);
                return;
            }
            sendCommand(player, command.get());
            this.stateActionStarted = true;
            return;
        }
        if (this.transferStage == 0
                && this.tick - this.transferStartedTick >= TRANSFER_WAIT_TICKS) {
            Optional<String> rtp = this.adapter.randomTeleportCommand(
                    this.rtpMode.getValue()
            );
            if (rtp.isEmpty()) {
                signal(BaseFinderFsm.Signal.TRANSFER_DONE);
                return;
            }
            sendCommand(player, rtp.get());
            this.transferStage = 1;
            this.transferStartedTick = this.tick;
            return;
        }
        if (this.transferStage == 1
                && this.tick - this.transferStartedTick >= RTP_WAIT_TICKS) {
            this.headingYaw = player.getYRot();
            this.currentAnarchy = this.currentAnarchy >= 999
                    ? 1
                    : this.currentAnarchy + 1;
            signal(BaseFinderFsm.Signal.TRANSFER_DONE);
        }
    }

    private void tickDescend(LocalPlayer player) {
        BlockPos target = descentTarget(player);
        if (!this.stateActionStarted) {
            this.navigator.pathTo(target, 1);
            this.segmentGoal = target;
            this.stateActionStarted = true;
            resetProgress(player);
        }
        if (player.blockPosition().distSqr(target) <= 4.0D) {
            this.searchStartedTick = this.tick;
            this.routeIndex = 0;
            signal(BaseFinderFsm.Signal.DESCENT_DONE);
            return;
        }
        if (stuck(player)) {
            signal(BaseFinderFsm.Signal.PATH_STUCK);
        }
    }

    private void tickSearch(LocalPlayer player) {
        if (!this.stateActionStarted) {
            this.segmentGoal = nextSearchGoal(player);
            this.navigator.pathTo(this.segmentGoal, 2);
            this.stateActionStarted = true;
            resetProgress(player);
        }
        if (player.blockPosition().distSqr(this.segmentGoal) <= 9.0D) {
            if (!this.routePoints.isEmpty()) {
                this.routeIndex = (this.routeIndex + 1) % this.routePoints.size();
            }
            if (this.heightMode.is("Smart") && this.routePoints.isEmpty()) {
                this.smartHeight += 15;
                if (this.smartHeight > 30) {
                    this.smartHeight = -60;
                }
            }
            this.stateActionStarted = false;
            return;
        }
        if (stuck(player)) {
            signal(BaseFinderFsm.Signal.PATH_STUCK);
        }
    }

    private void tickBypass(LocalPlayer player) {
        if (!this.stateActionStarted) {
            Vec3 forward = horizontalDirection(this.headingYaw);
            Vec3 side = new Vec3(-forward.z, 0.0D, forward.x).scale(
                    16.0D * this.bypassSide
            );
            Vec3 destination = player.position().add(forward.scale(24.0D)).add(side);
            this.segmentGoal = BlockPos.containing(
                    destination.x,
                    currentSearchHeight(),
                    destination.z
            );
            this.navigator.pathTo(this.segmentGoal, 2);
            this.stateActionStarted = true;
            resetProgress(player);
        }
        if (player.blockPosition().distSqr(this.segmentGoal) <= 9.0D
                || this.machine.ticksInState(this.tick) > 400L) {
            this.bypassSide *= -1;
            this.headingYaw += 90.0F;
            signal(BaseFinderFsm.Signal.BYPASS_DONE);
        }
    }

    private void tickScanner(Minecraft client, LocalPlayer player) {
        if (!this.scanner.isRunning() && this.tick >= this.nextScanTick
                && (this.lastScanCenter == null
                || this.lastScanCenter.distSqr(player.blockPosition())
                >= square(Math.max(4, this.scanRadius.getValue().intValue() / 3)))) {
            this.lastScanCenter = player.blockPosition();
            this.scanner.begin(
                    this.lastScanCenter,
                    this.scanRadius.getValue().intValue(),
                    this.targetBlocks
            );
        }
        this.scanner.scan(client.level, SCAN_BUDGET_PER_TICK).ifPresent(result -> {
            this.scanner.reset();
            this.nextScanTick = this.tick + 40L;
            if (result.matches().size() >= this.minContainers.getValue().intValue()) {
                this.foundPosition = result.nearestTo(player.blockPosition())
                        .orElse(result.scanCenter());
                this.navigator.cancel();
                ChatUtil.info(
                        "Container cluster found near "
                                + this.foundPosition.getX() + ", "
                                + this.foundPosition.getY() + ", "
                                + this.foundPosition.getZ()
                );
                signal(BaseFinderFsm.Signal.CLUSTER_FOUND);
                client.execute(() -> {
                    if (isEnabled()) {
                        setEnabled(false);
                    }
                });
            }
        });
    }

    private BlockPos descentTarget(LocalPlayer player) {
        if (!this.routePoints.isEmpty()) {
            BlockPos first = this.routePoints.getFirst();
            return new BlockPos(first.getX(), first.getY(), first.getZ());
        }
        return new BlockPos(
                player.getBlockX(),
                currentSearchHeight(),
                player.getBlockZ()
        );
    }

    private BlockPos nextSearchGoal(LocalPlayer player) {
        if (!this.routePoints.isEmpty()) {
            return this.routePoints.get(this.routeIndex);
        }
        Vec3 direction = horizontalDirection(this.headingYaw);
        Vec3 destination = player.position().add(
                direction.scale(this.segmentLength.getValue())
        );
        return BlockPos.containing(
                destination.x,
                currentSearchHeight(),
                destination.z
        );
    }

    private int currentSearchHeight() {
        return this.heightMode.is("Smart")
                ? this.smartHeight
                : this.searchHeight.getValue().intValue();
    }

    private boolean stuck(LocalPlayer player) {
        if (this.tick - this.lastProgressTick < STUCK_CHECK_TICKS) {
            return false;
        }
        BlockPos current = player.blockPosition();
        boolean stuck = this.lastProgressPosition != null
                && current.distSqr(this.lastProgressPosition) < 4.0D;
        this.lastProgressPosition = current;
        this.lastProgressTick = this.tick;
        return stuck;
    }

    private void resetProgress(LocalPlayer player) {
        this.lastProgressPosition = player.blockPosition();
        this.lastProgressTick = this.tick;
    }

    private String safetyReason(Minecraft client, LocalPlayer player) {
        PveManagerFeature manager = PveManagerFeature.INSTANCE;
        if (this.machine.is(BaseFinderFsm.State.FOUND)
                || this.machine.is(BaseFinderFsm.State.ERROR)
                || this.machine.is(BaseFinderFsm.State.IDLE)) {
            return null;
        }
        if (!player.isAlive() || player.getHealth() + player.getAbsorptionAmount()
                < manager.minimumHealth.getValue()) {
            return "low health";
        }
        if (!player.isCreative()) {
            int slot = MiningInventory.bestPickaxeSlot(player);
            if (slot < 0) {
                return "pickaxe missing";
            }
            if (MiningInventory.durabilityPercent(player.getInventory().getItem(slot))
                    <= manager.minimumToolDurability.getValue()) {
                return "pickaxe durability";
            }
        }
        double radius = manager.playerRadius.getValue();
        if (manager.pauseNearPlayers.getValue() && radius > 0.0D) {
            double squared = radius * radius;
            for (Player other : client.level.players()) {
                if (other != player && other.isAlive() && !other.isSpectator()
                        && other.distanceToSqr(player) <= squared) {
                    return "nearby player";
                }
            }
        }
        return null;
    }

    private void pauseForSafety(String reason) {
        if (!this.machine.is(BaseFinderFsm.State.PAUSED)) {
            this.resumeState = this.machine.state();
        }
        this.pauseReason = reason;
        this.safetyPaused = true;
        this.navigator.cancel();
        transition(BaseFinderFsm.State.PAUSED);
    }

    private boolean shouldTransferAgain() {
        return this.usesServerTransfer
                && this.machine.is(BaseFinderFsm.State.SEARCHING)
                && this.tick - this.searchStartedTick
                >= this.transferInterval.getValue().longValue() * 60L * 20L;
    }

    private void signal(BaseFinderFsm.Signal signal) {
        transition(BaseFinderFsm.next(
                this.machine.state(),
                signal,
                this.usesServerTransfer
        ));
    }

    private void transition(BaseFinderFsm.State next) {
        if (this.machine.transition(next, this.tick)) {
            this.stateActionStarted = false;
            this.segmentGoal = null;
            if (next != BaseFinderFsm.State.PAUSED) {
                this.safetyPaused = false;
                this.pauseReason = null;
            }
            if (next == BaseFinderFsm.State.SEARCHING) {
                this.searchStartedTick = this.tick;
            }
        }
    }

    private static Vec3 horizontalDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians)).normalize();
    }

    private static void sendCommand(LocalPlayer player, String command) {
        if (player != null && command != null && !command.isBlank()) {
            player.connection.sendCommand(
                    command.charAt(0) == '/' ? command.substring(1) : command
            );
        }
    }

    private static long square(int value) {
        return (long) value * value;
    }

    private void cleanup() {
        Minecraft client = Minecraft.getInstance();
        try {
            this.navigator.end();
        } catch (LinkageError | RuntimeException ignored) {
        }
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        this.scanner.reset();
        this.machine.reset(this.tick);
        this.stateActionStarted = false;
        this.segmentGoal = null;
        this.snapshot.restore(client);
    }
}
