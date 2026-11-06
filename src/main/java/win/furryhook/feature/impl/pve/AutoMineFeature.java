package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.PveStateMachine;
import win.furryhook.pve.mining.AutoMineFsm;
import win.furryhook.pve.mining.MiningInventory;
import win.furryhook.pve.mining.MiningParsers;
import win.furryhook.pve.mining.MineProfiles;
import win.furryhook.pve.mining.MiningToolProfile;
import win.furryhook.pve.mining.MiningServerAdapter;
import win.furryhook.pve.mining.MiningServerAdapters;
import win.furryhook.pve.mining.MiningSessionSnapshot;
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.navigation.Navigator;
import win.furryhook.pve.server.ServerProfile;
import win.furryhook.utils.inventory.InventoryUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.text.ChatUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class AutoMineFeature extends PveFeature {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoMineFeature.class);
    private static final int DIAGNOSTIC_INTERVAL_TICKS = 100;
    private static final int PROFILE_UPDATE_INTERVAL_TICKS = 20;
    private static final int ARENA_ENTRY_TIMEOUT_TICKS = 1_200;
    private static final int MAX_LOGGED_TARGETS = 16;
    private static final String DEFAULT_TARGETS = String.join(",",
            "coal_ore", "deepslate_coal_ore",
            "iron_ore", "deepslate_iron_ore",
            "copper_ore", "deepslate_copper_ore",
            "gold_ore", "deepslate_gold_ore", "nether_gold_ore",
            "redstone_ore", "deepslate_redstone_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "diamond_ore", "deepslate_diamond_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "nether_quartz_ore", "ancient_debris"
    );
    private static final String DEFAULT_DEPOSIT_ITEMS = String.join(",",
            "coal", "raw_iron", "raw_copper", "raw_gold", "gold_nugget",
            "redstone", "lapis_lazuli", "diamond", "emerald",
            "nether_quartz", "amethyst_shard", "ancient_debris"
    );
    private static final int TELEPORT_SETTLE_TICKS = 80;
    private static final int NAVIGATION_TIMEOUT_TICKS = 1_200;
    private static final int OPEN_TIMEOUT_TICKS = 240;

    public final ModeSetting mineProfile = register(new ModeSetting(
            "Mine Profile",
            MineProfiles.MODE_AUTO,
            MineProfiles.MODE_AUTO,
            MineProfiles.MODE_FUNTIME,
            MineProfiles.MODE_HOLYWORLD,
            MineProfiles.MODE_NONE
    ));
    public final TextSetting targets = register(new TextSetting(
            "Targets", DEFAULT_TARGETS, 1_024
    ));
    public final TextSetting route = register(new TextSetting(
            "Route", "", 1_024
    ));
    public final NumberSetting routeRadius = register(new NumberSetting(
            "Route Radius", 2.0D, 0.0D, 12.0D, 1.0D, " blocks"
    ));
    public final ModeSetting depositMode = register(new ModeSetting(
            "Deposit Mode", "None", "None", "Chest"
    ));
    public final TextSetting depositPosition = register(new TextSetting(
            "Deposit Position", "", 64
    ));
    public final TextSetting depositCommand = register(new TextSetting(
            "Deposit Command", "", 64
    ));
    public final TextSetting depositItems = register(new TextSetting(
            "Deposit Items", DEFAULT_DEPOSIT_ITEMS, 512
    ));
    public final NumberSetting depositAtFreeSlots = register(new NumberSetting(
            "Deposit At Free Slots", 2.0D, 0.0D, 12.0D, 1.0D, ""
    ));

    private final Navigator navigator;
    private final MiningSessionSnapshot snapshot = new MiningSessionSnapshot();
    private final PveStateMachine<AutoMineFsm.State> machine =
            new PveStateMachine<>(AutoMineFsm.State.IDLE);

    private List<Block> targetBlocks = List.of();
    private Set<Block> targetBlockSet = Set.of();
    private List<BlockPos> routePoints = List.of();
    private Set<String> depositItemIds = Set.of();
    private MiningServerAdapter adapter;
    private BlockPos configuredDeposit;
    private BlockPos activeDeposit;
    private String depositTravelCommand;
    private long tick;
    private int routeIndex;
    private boolean stateActionStarted;
    private boolean safetyPaused;
    private AutoMineFsm.State resumeState = AutoMineFsm.State.MINING;
    private String pauseReason;
    private boolean openedDepositContainer;
    private int mineInvocationCount;
    private String lastSafetyReason;
    private boolean depositResourcesHeld;
    private boolean fullInventoryWarningLogged;
    private long nonSafetyRetryAtTick;
    private boolean externalInventoryPaused;
    private MineProfiles.Profile activeMineProfile;
    private long nextMineProfileUpdateTick;
    private final Set<BlockPos> observedTargetBlocks = new HashSet<>();
    private long sessionStartedMillis;
    private long sessionEndedMillis;
    private int minedOres;
    private MiningToolProfile toolProfile = MiningToolProfile.STANDARD;
    private boolean enteringArena;
    private BlockPos arenaEntryGoal;
    private long arenaEntryStartedTick;
    private int consecutiveMineFailures;

    public AutoMineFeature() {
        this(BaritoneNavigator.INSTANCE);
    }

    AutoMineFeature(Navigator navigator) {
        super(
                "AutoMine",
                "Runs configurable Baritone mining routes with safe deposits",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.BOT,
                AutomationResource.MOVEMENT,
                AutomationResource.ROTATION,
                AutomationResource.NAVIGATION
        );
        this.navigator = navigator;
    }

    @Override
    protected void onPveEnable() {
        this.sessionStartedMillis = System.currentTimeMillis();
        this.sessionEndedMillis = 0L;
        this.minedOres = 0;
        this.toolProfile = MiningToolProfile.STANDARD;
        this.observedTargetBlocks.clear();
        this.enteringArena = false;
        this.arenaEntryGoal = null;
        this.arenaEntryStartedTick = 0L;
        this.consecutiveMineFailures = 0;
        this.tick = 0L;
        this.routeIndex = 0;
        this.openedDepositContainer = false;
        this.mineInvocationCount = 0;
        this.lastSafetyReason = null;
        this.depositResourcesHeld = false;
        this.fullInventoryWarningLogged = false;
        this.nonSafetyRetryAtTick = 0L;
        this.externalInventoryPaused = false;
        this.activeMineProfile = null;
        this.nextMineProfileUpdateTick = 0L;
        this.snapshot.capture(Minecraft.getInstance().player);
        ServerProfile detectedProfile = PveManagerFeature.INSTANCE
                .resolveServerProfile(Minecraft.getInstance());
        this.adapter = MiningServerAdapters.forProfile(detectedProfile);
        this.targetBlocks = MiningInventory.resolveBlocks(
                MiningParsers.identifiers(this.targets.getValue())
        );
        this.targetBlockSet = Set.copyOf(this.targetBlocks);
        if (this.targetBlocks.isEmpty()) {
            throw new IllegalStateException("AutoMine has no valid target blocks");
        }
        this.routePoints = MiningParsers.route(this.route.getValue()).stream()
                .map(MiningParsers.GridPoint::toBlockPos)
                .toList();
        this.depositItemIds = MiningParsers.identifiers(this.depositItems.getValue());
        this.configuredDeposit = MiningParsers.point(this.depositPosition.getValue())
                .map(MiningParsers.GridPoint::toBlockPos)
                .orElse(null);
        this.depositTravelCommand = resolveDepositCommand();
        if (!this.navigator.isAvailable()) {
            throw new IllegalStateException("Baritone is unavailable");
        }
        this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                NavigationOptions.breakingOnly()
        ));
        LocalPlayer player = Minecraft.getInstance().player;
        updateMineProfile(detectedProfile, player, true);
        updateToolProfile(detectedProfile, player, true);
        if (this.activeMineProfile != null) {
            this.navigator.setMineRenderColor(Theme.getAccent());
        }
        logConfiguration(detectedProfile);
        this.machine.reset(this.tick);
        AutoMineFsm.State initialState = AutoMineFsm.next(
                this.machine.state(),
                AutoMineFsm.Signal.START,
                hasRoute(),
                hasDeposit(),
                false
        );
        logInfo("signal=START state={} next={}", this.machine.state(), initialState);
        transition(initialState);
    }

    @Override
    protected void onPveDisable() {
        logInfo(
                "disable state={} stateTicks={} mineInvocations={} navigator={}",
                this.machine.state(),
                this.machine.ticksInState(this.tick),
                this.mineInvocationCount,
                this.navigator.diagnostics()
        );
        cleanup();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        logInfo("preempted reason={} state={}", reason, this.machine.state());
        cleanup();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        this.tick++;
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            if (this.tick % DIAGNOSTIC_INTERVAL_TICKS == 0L) {
                logInfo("tick skipped: player/level/gameMode missing");
            }
            return;
        }
        this.snapshot.capture(player);
        List<BlockPos> miningTargets = this.navigator.miningTargets();
        observeTargets(miningTargets);
        observeAoeTargets(client.level, miningTargets);
        updateObservedTargets(client.level);
        if (this.activeMineProfile != null) {
            this.navigator.setMineRenderColor(Theme.getAccent());
        }
        if (this.tick >= this.nextMineProfileUpdateTick) {
            ServerProfile currentServer =
                    PveManagerFeature.INSTANCE.resolveServerProfile(client);
            updateMineProfile(
                    currentServer,
                    player,
                    false
            );
            updateToolProfile(currentServer, player, false);
            this.nextMineProfileUpdateTick =
                    this.tick + PROFILE_UPDATE_INTERVAL_TICKS;
        }
        boolean inventoryBusy = PveAutomationCoordinator.INSTANCE.isClaimedByOther(
                this,
                AutomationResource.INVENTORY
        );
        if (inventoryBusy) {
            if (!this.externalInventoryPaused) {
                this.externalInventoryPaused = true;
                this.navigator.cancel();
                if (!this.machine.is(AutoMineFsm.State.WARPING)) {
                    this.stateActionStarted = false;
                }
                logInfo(
                        "Baritone paused for external inventory action owner={}",
                        PveAutomationCoordinator.INSTANCE.ownerId(
                                AutomationResource.INVENTORY
                        )
                );
            }
            return;
        }
        if (this.externalInventoryPaused) {
            this.externalInventoryPaused = false;
            this.consecutiveMineFailures = 0;
            if (!this.machine.is(AutoMineFsm.State.WARPING)) {
                this.stateActionStarted = false;
            }
            logInfo("external inventory action settled; resuming state={}",
                    this.machine.state());
        }
        if (this.tick % DIAGNOSTIC_INTERVAL_TICKS == 0L) {
            logDiagnostics(client, player, "periodic");
        }
        if (this.machine.is(AutoMineFsm.State.PAUSED) && !this.safetyPaused) {
            if (this.tick < this.nonSafetyRetryAtTick) {
                return;
            }
            AutoMineFsm.State retryState = AutoMineFsm.resume(
                    this.resumeState,
                    hasRoute(),
                    false
            );
            logInfo(
                    "retrying non-safety pause reason={} next={}",
                    this.pauseReason,
                    retryState
            );
            transition(retryState);
        }

        String unsafe = safetyReason(client, player);
        if (unsafe != null) {
            if (!unsafe.equals(this.lastSafetyReason)) {
                logWarn("safety pause reason={} state={}", unsafe, this.machine.state());
                notifySafetyPause(player, unsafe);
                logDiagnostics(client, player, "safety-pause");
                this.lastSafetyReason = unsafe;
            }
            pauseForSafety(unsafe);
            return;
        }
        if (this.lastSafetyReason != null) {
            logInfo("safety cleared previousReason={}", this.lastSafetyReason);
            this.lastSafetyReason = null;
        }
        if (this.machine.is(AutoMineFsm.State.PAUSED)) {
            if (!this.safetyPaused) {
                return;
            }
            transition(AutoMineFsm.resume(
                    this.resumeState,
                    hasRoute(),
                    false
            ));
            logInfo("safety resume state={}", this.machine.state());
        }

        switch (this.machine.state()) {
            case IDLE, PAUSED, ERROR -> {
            }
            case WARPING -> signal(AutoMineFsm.Signal.TELEPORT_DONE);
            case ROUTING -> tickRoute(player, false);
            case MINING -> tickMining(client.level, player);
            case TO_DEPOSIT -> tickToDeposit(player);
            case OPENING_DEPOSIT -> tickOpenDeposit(client, player);
            case DEPOSITING -> tickDeposit(player);
            case RETURNING -> tickRoute(player, true);
        }
    }

    public AutoMineFsm.State getState() {
        return this.machine.state();
    }

    public String currentPhaseName() {
        return switch (this.machine.state()) {
            case IDLE -> "Idle";
            case WARPING -> "Warp to mine";
            case ROUTING -> "Following route";
            case MINING -> "Mining";
            case TO_DEPOSIT -> "Going to deposit";
            case OPENING_DEPOSIT -> "Opening deposit";
            case DEPOSITING -> "Depositing ore";
            case RETURNING -> "Returning";
            case PAUSED -> "Paused: " + (this.pauseReason == null ? "safety" : this.pauseReason);
            case ERROR -> "Error";
        };
    }

    public Status currentStatus() {
        long end = isEnabled()
                ? System.currentTimeMillis()
                : this.sessionEndedMillis;
        long elapsed = this.sessionStartedMillis <= 0L || end <= 0L
                ? 0L
                : Math.max(0L, end - this.sessionStartedMillis);
        return new Status(
                isEnabled(),
                currentPhaseName(),
                this.minedOres,
                elapsed,
                this.navigator.miningTargets().size(),
                this.activeMineProfile == null
                        ? "None"
                        : this.activeMineProfile.id(),
                this.toolProfile.displayName()
        );
    }

    public Optional<MineProfiles.Profile> getActiveMineProfile() {
        return Optional.ofNullable(this.activeMineProfile);
    }

    private void tickRoute(LocalPlayer player, boolean returning) {
        if (this.routePoints.isEmpty()) {
            signal(AutoMineFsm.Signal.ARRIVED);
            return;
        }
        int index = returning ? this.routePoints.size() - 1 : this.routeIndex;
        BlockPos target = this.routePoints.get(index);
        if (!this.stateActionStarted) {
            logInfo("route target index={} returning={} pos={} radius={}",
                    index, returning, target, this.routeRadius.getValue());
            this.navigator.pathTo(target, this.routeRadius.getValue().intValue());
            this.stateActionStarted = true;
        }
        if (this.navigator.isMining() && this.navigator.isPathing()) {
            this.consecutiveMineFailures = 0;
        }
        if (player.blockPosition().distSqr(target)
                <= square(this.routeRadius.getValue() + 0.75D)) {
            if (!returning && ++this.routeIndex < this.routePoints.size()) {
                this.stateActionStarted = false;
                return;
            }
            signal(AutoMineFsm.Signal.ARRIVED);
            return;
        }
        if (this.machine.ticksInState(this.tick) > NAVIGATION_TIMEOUT_TICKS) {
            fail();
        }
    }

    private void tickMining(ClientLevel level, LocalPlayer player) {
        if (this.enteringArena || isOutsideMine(player)) {
            tickArenaEntry(level, player);
            return;
        }
        if (!this.stateActionStarted) {
            this.mineInvocationCount++;
            logInfo(
                    "mine start invocation={} targets={} navigatorBefore={}",
                    this.mineInvocationCount,
                    resolvedTargetIds(),
                    this.navigator.diagnostics()
            );
            this.navigator.mine(0, this.targetBlocks.toArray(Block[]::new));
            this.stateActionStarted = true;
        }
        int freeSlots = MiningInventory.freeSlots(player);
        if (freeSlots <= this.depositAtFreeSlots.getValue().intValue()) {
            if (!hasDeposit()) {
                if (!this.fullInventoryWarningLogged) {
                    logWarn(
                            "inventory threshold reached freeSlots={} but deposit is "
                                    + "not configured; continuing to mine",
                            freeSlots
                    );
                    this.fullInventoryWarningLogged = true;
                }
            } else if (acquireDepositResources()) {
                logInfo("inventory threshold reached freeSlots={} -> deposit", freeSlots);
                signal(AutoMineFsm.Signal.INVENTORY_FULL);
                return;
            }
        } else {
            this.fullInventoryWarningLogged = false;
        }
        if (!this.navigator.isMining()
                && this.machine.ticksInState(this.tick) > 0
                && this.machine.ticksInState(this.tick) % 10L == 0L) {
            this.consecutiveMineFailures++;
            if (shouldRecoverViaSurface(player)) {
                logWarn(
                        "mine path failed {} times; routing through ladder top",
                        this.consecutiveMineFailures
                );
                this.enteringArena = false;
                tickArenaEntry(level, player);
                return;
            }
            this.mineInvocationCount++;
            logInfo(
                    "mine retry invocation={} stateTicks={} navigatorBefore={}",
                    this.mineInvocationCount,
                    this.machine.ticksInState(this.tick),
                    this.navigator.diagnostics()
            );
            this.navigator.mine(0, this.targetBlocks.toArray(Block[]::new));
        }
    }

    private boolean shouldRecoverViaSurface(LocalPlayer player) {
        if (this.activeMineProfile == null
                || player.getBlockY() >= this.activeMineProfile.max().getY()) {
            return false;
        }
        boolean hasHigherTarget = this.observedTargetBlocks.stream()
                .anyMatch(position -> position.getY() > player.getBlockY() + 1);
        return this.consecutiveMineFailures >= 4
                || this.consecutiveMineFailures >= 2 && hasHigherTarget;
    }

    private void tickArenaEntry(ClientLevel level, LocalPlayer player) {
        if (this.activeMineProfile == null) {
            this.enteringArena = false;
            this.arenaEntryGoal = null;
            return;
        }
        if (!this.enteringArena) {
            this.navigator.cancel();
            this.arenaEntryGoal = findArenaEntry(level, player);
            this.arenaEntryStartedTick = this.tick;
            this.enteringArena = true;
            this.stateActionStarted = false;
            logInfo(
                    "arena entry start player={} goal={} profile={}",
                    player.blockPosition(),
                    this.arenaEntryGoal,
                    this.activeMineProfile.id()
            );
            this.navigator.pathTo(this.arenaEntryGoal, 0);
            return;
        }

        boolean reached = !isOutsideMine(player)
                && player.getBlockY() >= this.activeMineProfile.max().getY() + 1;
        if (reached) {
            logInfo("arena entry reached player={} goal={}",
                    player.blockPosition(), this.arenaEntryGoal);
            this.navigator.cancel();
            this.enteringArena = false;
            this.arenaEntryGoal = null;
            this.stateActionStarted = false;
            this.consecutiveMineFailures = 0;
            return;
        }
        if (!this.navigator.isPathing()
                && (this.tick - this.arenaEntryStartedTick) % 20L == 0L) {
            this.navigator.pathTo(this.arenaEntryGoal, 0);
        }
        if (this.tick - this.arenaEntryStartedTick
                > ARENA_ENTRY_TIMEOUT_TICKS) {
            logWarn(
                    "arena entry timed out player={} goal={}; retrying mining",
                    player.blockPosition(),
                    this.arenaEntryGoal
            );
            this.navigator.cancel();
            this.enteringArena = false;
            this.arenaEntryGoal = null;
            this.stateActionStarted = false;
        }
    }

    private boolean isOutsideMine(LocalPlayer player) {
        if (this.activeMineProfile == null) {
            return false;
        }
        BlockPos position = player.blockPosition();
        BlockPos min = this.activeMineProfile.min();
        BlockPos max = this.activeMineProfile.max();
        return position.getX() < min.getX()
                || position.getX() > max.getX()
                || position.getZ() < min.getZ()
                || position.getZ() > max.getZ()
                || position.getY() < min.getY()
                || position.getY() > max.getY() + 1;
    }

    private BlockPos findArenaEntry(ClientLevel level, LocalPlayer player) {
        BlockPos min = this.activeMineProfile.min();
        BlockPos max = this.activeMineProfile.max();
        int topY = max.getY() + 1;
        List<BlockPos> ladderTops = new ArrayList<>();
        for (int x = min.getX() - 4; x <= max.getX() + 4; x++) {
            for (int z = min.getZ() - 4; z <= max.getZ() + 4; z++) {
                BlockPos lastLadder = null;
                for (int y = min.getY() - 2; y <= max.getY() + 3; y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (level.getBlockState(position).is(Blocks.LADDER)) {
                        lastLadder = position;
                    } else if (lastLadder != null) {
                        ladderTops.add(lastLadder);
                        lastLadder = null;
                    }
                }
                if (lastLadder != null) {
                    ladderTops.add(lastLadder);
                }
            }
        }
        BlockPos nearest = new BlockPos(
                Math.clamp(player.getBlockX(), min.getX(), max.getX()),
                topY,
                Math.clamp(player.getBlockZ(), min.getZ(), max.getZ())
        );
        BlockPos best = nearest;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                BlockPos candidate = new BlockPos(x, topY, z);
                if (!level.getBlockState(candidate).isAir()
                        || !level.getBlockState(candidate.above()).isAir()) {
                    continue;
                }
                double distance = ladderTops.isEmpty()
                        ? player.distanceToSqr(Vec3.atCenterOf(candidate))
                        : ladderTops.stream()
                        .mapToDouble(ladder -> ladder.distSqr(candidate))
                        .min()
                        .orElse(Double.POSITIVE_INFINITY)
                        + player.distanceToSqr(Vec3.atCenterOf(candidate)) * 0.01D;
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void tickToDeposit(LocalPlayer player) {
        if (!acquireDepositResources()) {
            return;
        }
        if (!this.stateActionStarted) {
            this.navigator.cancel();
            this.activeDeposit = this.configuredDeposit;
            logInfo(
                    "deposit travel start configuredPos={} commandConfigured={}",
                    this.activeDeposit,
                    this.depositTravelCommand != null
            );
            if (this.activeDeposit != null) {
                this.navigator.pathTo(this.activeDeposit, 3);
            } else if (this.depositTravelCommand != null) {
                if (!sendCommandWithClaim(player, this.depositTravelCommand)) {
                    return;
                }
            } else {
                fail();
                return;
            }
            this.stateActionStarted = true;
        }
        if (this.activeDeposit != null) {
            if (player.blockPosition().distSqr(this.activeDeposit) <= 16.0D) {
                signal(AutoMineFsm.Signal.ARRIVED);
            } else if (this.machine.ticksInState(this.tick) > NAVIGATION_TIMEOUT_TICKS) {
                fail();
            }
            return;
        }
        if (this.machine.ticksInState(this.tick) >= TELEPORT_SETTLE_TICKS) {
            signal(AutoMineFsm.Signal.ARRIVED);
        }
    }

    private void tickOpenDeposit(Minecraft client, LocalPlayer player) {
        if (!acquireDepositResources()) {
            return;
        }
        if (player.containerMenu != player.inventoryMenu) {
            this.openedDepositContainer = true;
            signal(AutoMineFsm.Signal.CONTAINER_OPEN);
            return;
        }
        if (this.activeDeposit == null || !isContainer(client, this.activeDeposit)) {
            this.activeDeposit = nearestContainer(client, player.blockPosition(), 6);
        }
        if (this.activeDeposit == null) {
            if (this.machine.ticksInState(this.tick) > OPEN_TIMEOUT_TICKS) {
                logWarn("deposit container not found near player={}", player.blockPosition());
                pause("No deposit container found", false);
            }
            return;
        }
        if (player.blockPosition().distSqr(this.activeDeposit) > 25.0D) {
            if (!this.stateActionStarted) {
                this.navigator.pathTo(this.activeDeposit, 3);
                this.stateActionStarted = true;
            }
            return;
        }
        if (!this.stateActionStarted || this.machine.ticksInState(this.tick) % 20L == 0L) {
            logInfo("opening deposit container pos={}", this.activeDeposit);
            client.gameMode.useItemOn(
                    player,
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3.atCenterOf(this.activeDeposit),
                            Direction.UP,
                            this.activeDeposit,
                            false
                    )
            );
            this.stateActionStarted = true;
        }
        if (this.machine.ticksInState(this.tick) > OPEN_TIMEOUT_TICKS) {
            pause("Deposit container did not open", false);
        }
    }

    private void tickDeposit(LocalPlayer player) {
        if (!acquireDepositResources()) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            pause("Deposit container closed", false);
            return;
        }
        if (this.machine.ticksInState(this.tick) > 600L) {
            pause("Deposit container is full or unresponsive", false);
            return;
        }
        if (this.machine.ticksInState(this.tick) % 3L != 0L) {
            return;
        }
        int playerStart = Math.max(0, menu.slots.size() - 36);
        for (int slot = playerStart; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (MiningInventory.isDepositItem(stack, this.depositItemIds)) {
                InventoryUtil.quickMoveSlot(slot);
                return;
            }
        }
        player.closeContainer();
        this.openedDepositContainer = false;
        this.routeIndex = 0;
        signal(AutoMineFsm.Signal.DEPOSIT_DONE);
    }

    private String safetyReason(Minecraft client, LocalPlayer player) {
        PveManagerFeature manager = PveManagerFeature.INSTANCE;
        if (!player.isAlive() || player.getHealth() + player.getAbsorptionAmount()
                < manager.minimumHealth.getValue()) {
            return "low health";
        }
        if (!player.isCreative()) {
            int pickaxeSlot = MiningInventory.bestPickaxeSlot(player);
            if (pickaxeSlot < 0) {
                return "pickaxe missing";
            }
            ItemStack pickaxe = player.getInventory().getItem(pickaxeSlot);
            if (MiningInventory.remainingDurability(pickaxe)
                    <= this.toolProfile.durabilityReserve(
                    pickaxe,
                    manager.minimumToolDurability.getValue()
            )) {
                return "pickaxe durability";
            }
        }
        double radius = manager.playerRadius.getValue();
        if (manager.pauseNearPlayers.getValue() && radius > 0.0D) {
            double radiusSquared = radius * radius;
            for (Player other : client.level.players()) {
                if (other != player && other.isAlive() && !other.isSpectator()
                        && other.distanceToSqr(player) <= radiusSquared) {
                    return "nearby player";
                }
            }
        }
        if (player.containerMenu != player.inventoryMenu
                && !this.machine.is(AutoMineFsm.State.DEPOSITING)
                && !this.machine.is(AutoMineFsm.State.OPENING_DEPOSIT)) {
            return "unexpected screen";
        }
        return null;
    }

    private void notifySafetyPause(LocalPlayer player, String reason) {
        if ("pickaxe durability".equals(reason)) {
            int slot = MiningInventory.bestPickaxeSlot(player);
            double durability = slot < 0
                    ? 0.0D
                    : MiningInventory.durabilityPercent(
                    player.getInventory().getItem(slot)
            );
            ChatUtil.info(String.format(
                    Locale.ROOT,
                    "AutoMine: пауза — прочность кирки %.1f%%, лимит %.1f%% "
                            + "+ запас %d блоков (%s)",
                    durability,
                    PveManagerFeature.INSTANCE.minimumToolDurability.getValue(),
                    this.toolProfile.maximumBlocksPerBreak(),
                    this.toolProfile.displayName()
            ));
            return;
        }
        ChatUtil.info("AutoMine: пауза — " + reason);
    }

    private void pauseForSafety(String reason) {
        if (!this.machine.is(AutoMineFsm.State.PAUSED)) {
            this.resumeState = this.machine.state();
        }
        pause(reason, true);
    }

    private void pause(String reason, boolean safety) {
        if (!this.machine.is(AutoMineFsm.State.PAUSED)) {
            this.resumeState = this.machine.state();
        }
        if (!this.machine.is(AutoMineFsm.State.PAUSED)
                || !Objects.equals(this.pauseReason, reason)
                || this.safetyPaused != safety) {
            logWarn(
                    "pause reason={} safety={} state={} navigator={}",
                    reason,
                    safety,
                    this.machine.state(),
                    this.navigator.diagnostics()
            );
        }
        this.pauseReason = reason;
        this.safetyPaused = safety;
        this.nonSafetyRetryAtTick = safety ? 0L : this.tick + 100L;
        this.navigator.cancel();
        transition(AutoMineFsm.State.PAUSED);
    }

    private void fail() {
        logWarn(
                "fail state={} stateTicks={} navigator={}",
                this.machine.state(),
                this.machine.ticksInState(this.tick),
                this.navigator.diagnostics()
        );
        this.navigator.cancel();
        transition(AutoMineFsm.State.ERROR);
    }

    private void signal(AutoMineFsm.Signal signal) {
        AutoMineFsm.State current = this.machine.state();
        AutoMineFsm.State next = AutoMineFsm.next(
                current,
                signal,
                hasRoute(),
                hasDeposit(),
                false
        );
        logInfo(
                "signal={} state={} next={} hasRoute={} hasDeposit={} serverWarp={}",
                signal,
                current,
                next,
                hasRoute(),
                hasDeposit(),
                false
        );
        transition(next);
    }

    private void transition(AutoMineFsm.State next) {
        AutoMineFsm.State previous = this.machine.state();
        long previousStateTicks = this.machine.ticksInState(this.tick);
        if (this.machine.transition(next, this.tick)) {
            if (isDepositState(previous) && !isDepositState(next)) {
                releaseDepositResources();
            }
            logInfo(
                    "transition {} -> {} tick={} previousStateTicks={}",
                    previous,
                    next,
                    this.tick,
                    previousStateTicks
            );
            this.stateActionStarted = false;
            if (next != AutoMineFsm.State.PAUSED) {
                this.pauseReason = null;
                this.safetyPaused = false;
                this.nonSafetyRetryAtTick = 0L;
            }
            if (next == AutoMineFsm.State.MINING) {
                this.activeDeposit = null;
            }
        }
    }

    private static boolean isDepositState(AutoMineFsm.State state) {
        return state == AutoMineFsm.State.TO_DEPOSIT
                || state == AutoMineFsm.State.OPENING_DEPOSIT
                || state == AutoMineFsm.State.DEPOSITING;
    }

    private boolean hasRoute() {
        return !this.routePoints.isEmpty();
    }

    private boolean hasDeposit() {
        return this.depositMode.is("Chest")
                && (this.configuredDeposit != null || this.depositTravelCommand != null);
    }

    private boolean acquireDepositResources() {
        if (this.depositResourcesHeld) {
            return true;
        }
        PveAutomationCoordinator coordinator = PveAutomationCoordinator.INSTANCE;
        if (coordinator.isClaimedByOther(this, AutomationResource.INVENTORY)
                || coordinator.isClaimedByOther(this, AutomationResource.SCREEN)) {
            if (this.tick % DIAGNOSTIC_INTERVAL_TICKS == 0L) {
                logInfo(
                        "waiting for deposit resources inventoryOwner={} screenOwner={}",
                        coordinator.ownerId(AutomationResource.INVENTORY),
                        coordinator.ownerId(AutomationResource.SCREEN)
                );
            }
            return false;
        }
        this.depositResourcesHeld = claim(
                AutomationResource.INVENTORY,
                AutomationResource.SCREEN
        );
        if (this.depositResourcesHeld) {
            logInfo("deposit resources acquired");
        }
        return this.depositResourcesHeld;
    }

    private void releaseDepositResources() {
        if (!this.depositResourcesHeld) {
            return;
        }
        release(AutomationResource.INVENTORY, AutomationResource.SCREEN);
        this.depositResourcesHeld = false;
        logInfo("deposit resources released");
    }

    private boolean sendCommandWithClaim(LocalPlayer player, String command) {
        PveAutomationCoordinator coordinator = PveAutomationCoordinator.INSTANCE;
        if (coordinator.isClaimedByOther(this, AutomationResource.CHAT)
                || !claim(AutomationResource.CHAT)) {
            return false;
        }
        try {
            sendCommand(player, command);
            return true;
        } finally {
            release(AutomationResource.CHAT);
        }
    }

    private String resolveDepositCommand() {
        if (!this.depositMode.is("Chest")) {
            return null;
        }
        Optional<String> configured =
                SafeServerCommand.normalize(this.depositCommand.getValue());
        if (configured.isPresent()) {
            return configured.get();
        }
        return this.adapter.homeCommand(
                PveManagerFeature.INSTANCE.resolvedHomeName()
        ).orElse(null);
    }

    private static void sendCommand(LocalPlayer player, String command) {
        if (player == null || command == null || command.isBlank()) {
            return;
        }
        player.connection.sendCommand(
                command.charAt(0) == '/' ? command.substring(1) : command
        );
    }

    private static boolean isContainer(Minecraft client, BlockPos position) {
        return client.level != null
                && client.level.hasChunkAt(position)
                && client.level.getBlockState(position).hasBlockEntity()
                && isContainerPath(client.level.getBlockState(position).getBlock()
                .getDescriptionId());
    }

    private static BlockPos nearestContainer(
            Minecraft client,
            BlockPos origin,
            int radius
    ) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int y = origin.getY() - radius; y <= origin.getY() + radius; y++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (!isContainer(client, position)) {
                        continue;
                    }
                    double distance = position.distSqr(origin);
                    if (distance < nearestDistance) {
                        nearest = position;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    private static boolean isContainerPath(String descriptionId) {
        return descriptionId != null
                && (descriptionId.endsWith(".chest")
                || descriptionId.endsWith(".trapped_chest")
                || descriptionId.endsWith(".barrel")
                || descriptionId.contains("shulker_box"));
    }

    private void updateMineProfile(ServerProfile detectedProfile,
                                   LocalPlayer player,
                                   boolean force) {
        MineProfiles.Profile selected = MineProfiles.select(
                this.mineProfile.getValue(),
                detectedProfile,
                player == null ? null : player.blockPosition()
        ).orElse(null);
        if (!force && Objects.equals(this.activeMineProfile, selected)) {
            return;
        }

        MineProfiles.Profile previous = this.activeMineProfile;
        this.activeMineProfile = selected;
        this.enteringArena = false;
        this.arenaEntryGoal = null;
        this.consecutiveMineFailures = 0;
        if (selected == null) {
            this.navigator.setMineBounds(null, null);
        } else {
            this.navigator.setMineBounds(selected.min(), selected.max());
            this.navigator.setMineRenderColor(Theme.getAccent());
        }

        if (!force) {
            this.navigator.cancel();
            if (!this.machine.is(AutoMineFsm.State.WARPING)) {
                this.stateActionStarted = false;
            }
        }
        logInfo(
                "mine profile changed previous={} selected={} bounds={}",
                previous == null ? "none" : previous.id(),
                selected == null ? "none" : selected.id(),
                selected == null
                        ? "unbounded"
                        : selected.min() + " -> " + selected.max()
        );
    }

    private void updateToolProfile(ServerProfile server,
                                   LocalPlayer player,
                                   boolean force) {
        int pickaxeSlot = player == null
                ? -1
                : MiningInventory.bestPickaxeSlot(player);
        ItemStack pickaxe = pickaxeSlot < 0
                ? ItemStack.EMPTY
                : player.getInventory().getItem(pickaxeSlot);
        MiningToolProfile detected = MiningToolProfile.detect(server, pickaxe);
        if (!force && detected == this.toolProfile) {
            return;
        }
        MiningToolProfile previous = this.toolProfile;
        this.toolProfile = detected;
        this.consecutiveMineFailures = 0;
        this.navigator.setMineAoeLevel(detected.bulldozerLevel());
        logInfo(
                "tool profile changed previous={} selected={} maxBlocksPerBreak={}",
                previous.displayName(),
                detected.displayName(),
                detected.maximumBlocksPerBreak()
        );
        if (!force && this.machine.is(AutoMineFsm.State.MINING)) {
            this.navigator.cancel();
            this.stateActionStarted = false;
        }
    }

    private void logConfiguration(ServerProfile detectedProfile) {
        PveManagerFeature manager = PveManagerFeature.INSTANCE;
        if (!manager.debugLogging.getValue()) {
            return;
        }
        LOGGER.info(
                "[AutoMine][settings] mineProfileMode={} selectedProfile={} "
                        + "profileBounds={} detectedServer={} adapter={} "
                        + "routeRadius={} "
                        + "depositMode={} depositPos={} depositCommandSet={} "
                        + "homeName={} freeSlotThreshold={} minHealth={} "
                        + "minDurability={} pauseNearPlayers={} avoidPlayersRadius={}",
                this.mineProfile.getValue(),
                this.activeMineProfile == null ? "none" : this.activeMineProfile.id(),
                profileDescription(),
                detectedProfile,
                this.adapter.getClass().getSimpleName(),
                this.routeRadius.getValue(),
                this.depositMode.getValue(),
                this.configuredDeposit,
                this.depositTravelCommand != null,
                manager.resolvedHomeName(),
                this.depositAtFreeSlots.getValue(),
                manager.minimumHealth.getValue(),
                manager.minimumToolDurability.getValue(),
                manager.pauseNearPlayers.getValue(),
                manager.playerRadius.getValue()
        );
        LOGGER.info(
                "[AutoMine][settings] targetsRaw={} resolvedTargets={} routeRaw={} "
                        + "routePoints={} depositItemsRaw={} resolvedDepositItems={}",
                this.targets.getValue(),
                resolvedTargetIds(),
                this.route.getValue(),
                this.routePoints,
                this.depositItems.getValue(),
                this.depositItemIds
        );
        LOGGER.info(
                "[AutoMine][settings] strictProfile={}",
                this.navigator.diagnostics()
        );
    }

    private void logDiagnostics(Minecraft client, LocalPlayer player, String cause) {
        if (client.level == null) {
            return;
        }
        LocalTargetScan scan = scanNearbyTargets(client.level, player);
        observeTargets(scan.targetPositions());
        if (!PveManagerFeature.INSTANCE.debugLogging.getValue()) {
            return;
        }
        List<BlockPos> baritoneTargets = this.navigator.miningTargets();
        int pickaxeSlot = MiningInventory.bestPickaxeSlot(player);
        ItemStack pickaxe = pickaxeSlot < 0
                ? ItemStack.EMPTY
                : player.getInventory().getItem(pickaxeSlot);
        String pickaxeDescription = pickaxe.isEmpty()
                ? "missing"
                : MiningInventory.itemId(pickaxe)
                + "@slot" + pickaxeSlot
                + " durability=" + String.format(
                Locale.ROOT,
                "%.1f%%",
                MiningInventory.durabilityPercent(pickaxe)
        );
        LOGGER.info(
                "[AutoMine][diag] cause={} tick={} state={} stateTicks={} "
                        + "player=({},{},{}) health={}/{} freeSlots={} safety={} "
                        + "pickaxe={} mineInvocations={} movementOwner={} navigationOwner={} "
                        + "inventoryOwner={} screenOwner={} depositResourcesHeld={}",
                cause,
                this.tick,
                this.machine.state(),
                this.machine.ticksInState(this.tick),
                player.getBlockX(),
                player.getBlockY(),
                player.getBlockZ(),
                String.format(Locale.ROOT, "%.1f", player.getHealth()),
                String.format(Locale.ROOT, "%.1f", player.getMaxHealth()),
                MiningInventory.freeSlots(player),
                safetyReason(client, player),
                pickaxeDescription,
                this.mineInvocationCount,
                PveAutomationCoordinator.INSTANCE.ownerId(AutomationResource.MOVEMENT),
                PveAutomationCoordinator.INSTANCE.ownerId(AutomationResource.NAVIGATION),
                PveAutomationCoordinator.INSTANCE.ownerId(AutomationResource.INVENTORY),
                PveAutomationCoordinator.INSTANCE.ownerId(AutomationResource.SCREEN),
                this.depositResourcesHeld
        );
        LOGGER.info(
                "[AutoMine][scan] radius={} scannedLoadedBlocks={} configuredTargets={} "
                        + "diamondsVisible={} counts={} nearest={} "
                        + "baritoneKnownTargets={} baritoneTargetSample={} profile={}",
                scan.radius(),
                scan.scannedLoadedBlocks(),
                scan.configuredTargetCount(),
                scan.diamondCount(),
                scan.counts(),
                scan.nearestDescription(),
                baritoneTargets.size(),
                baritoneTargets.stream().limit(MAX_LOGGED_TARGETS).toList(),
                profileDescription()
        );
        LOGGER.info(
                "[AutoMine][view] crosshair={} navigator={}",
                crosshairDescription(client),
                this.navigator.diagnostics()
        );
    }

    private void observeTargets(List<BlockPos> positions) {
        for (BlockPos position : positions) {
            this.observedTargetBlocks.add(position.immutable());
        }
    }

    private void observeAoeTargets(ClientLevel level, List<BlockPos> centers) {
        if (this.toolProfile == MiningToolProfile.STANDARD) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (BlockPos center : centers) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        cursor.set(
                                center.getX() + dx,
                                center.getY() + dy,
                                center.getZ() + dz
                        );
                        if (this.targetBlockSet.contains(
                                level.getBlockState(cursor).getBlock()
                        )) {
                            this.observedTargetBlocks.add(cursor.immutable());
                        }
                    }
                }
            }
        }
    }

    private void updateObservedTargets(ClientLevel level) {
        Iterator<BlockPos> iterator = this.observedTargetBlocks.iterator();
        while (iterator.hasNext()) {
            BlockPos position = iterator.next();
            if (!level.hasChunkAt(position)) {
                continue;
            }
            if (!this.targetBlockSet.contains(level.getBlockState(position).getBlock())) {
                iterator.remove();
                this.minedOres++;
            }
        }
    }

    private LocalTargetScan scanNearbyTargets(ClientLevel level, LocalPlayer player) {
        int radius = NavigationOptions.breakingOnly().mineSearchRadius();
        int radiusSq = radius * radius;
        BlockPos origin = player.blockPosition();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<TargetHit> hits = new ArrayList<>();
        int scanned = 0;
        int configuredTargets = 0;
        int diamonds = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    cursor.set(
                            origin.getX() + dx,
                            origin.getY() + dy,
                            origin.getZ() + dz
                    );
                    if (!level.hasChunkAt(cursor)) {
                        continue;
                    }
                    scanned++;
                    Block block = level.getBlockState(cursor).getBlock();
                    boolean configured = this.targetBlockSet.contains(block);
                    boolean diamond = block == Blocks.DIAMOND_ORE
                            || block == Blocks.DEEPSLATE_DIAMOND_ORE;
                    if (!configured && !diamond) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                    counts.merge(id, 1, Integer::sum);
                    if (configured) {
                        configuredTargets++;
                    }
                    if (diamond) {
                        diamonds++;
                    }
                    BlockPos position = cursor.immutable();
                    hits.add(new TargetHit(
                            id,
                            position,
                            Math.sqrt(player.position().distanceToSqr(Vec3.atCenterOf(position))),
                            configured,
                            diamond
                    ));
                }
            }
        }

        hits.sort(Comparator.comparingDouble(TargetHit::distance));
        String nearest = hits.isEmpty()
                ? "none"
                : hits.stream()
                .limit(MAX_LOGGED_TARGETS)
                .map(TargetHit::description)
                .reduce((left, right) -> left + "; " + right)
                .orElse("none")
                + (hits.size() > MAX_LOGGED_TARGETS
                ? "; +" + (hits.size() - MAX_LOGGED_TARGETS) + " more"
                : "");
        return new LocalTargetScan(
                radius,
                scanned,
                configuredTargets,
                diamonds,
                Map.copyOf(counts),
                nearest,
                hits.stream()
                        .filter(TargetHit::configured)
                        .map(TargetHit::position)
                        .toList()
        );
    }

    private String crosshairDescription(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK
                || client.level == null) {
            return "none";
        }
        Block block = client.level.getBlockState(hit.getBlockPos()).getBlock();
        return BuiltInRegistries.BLOCK.getKey(block)
                + "@"
                + hit.getBlockPos()
                + " configuredTarget="
                + this.targetBlockSet.contains(block);
    }

    private List<String> resolvedTargetIds() {
        return this.targetBlocks.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .sorted()
                .toList();
    }

    private String profileDescription() {
        if (this.activeMineProfile == null) {
            return "none";
        }
        return this.activeMineProfile.id()
                + " min=" + this.activeMineProfile.min()
                + " max=" + this.activeMineProfile.max();
    }

    private void logInfo(String message, Object... arguments) {
        if (PveManagerFeature.INSTANCE.debugLogging.getValue()) {
            LOGGER.info("[AutoMine] " + message, arguments);
        }
    }

    private void logWarn(String message, Object... arguments) {
        if (PveManagerFeature.INSTANCE.debugLogging.getValue()) {
            LOGGER.warn("[AutoMine] " + message, arguments);
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private void cleanup() {
        Minecraft client = Minecraft.getInstance();
        if (this.sessionStartedMillis > 0L) {
            this.sessionEndedMillis = System.currentTimeMillis();
        }
        this.observedTargetBlocks.clear();
        try {
            this.navigator.end();
        } catch (LinkageError | RuntimeException ignored) {
        }
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        if (this.openedDepositContainer && client.player != null
                && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
        releaseDepositResources();
        this.openedDepositContainer = false;
        this.targetBlockSet = Set.of();
        this.externalInventoryPaused = false;
        this.activeMineProfile = null;
        this.nextMineProfileUpdateTick = 0L;
        this.enteringArena = false;
        this.arenaEntryGoal = null;
        this.consecutiveMineFailures = 0;
        this.machine.reset(this.tick);
        this.stateActionStarted = false;
        this.snapshot.restore(client);
    }

    public record Status(
            boolean active,
            String phase,
            int minedOres,
            long elapsedMillis,
            int knownTargets,
            String profile,
            String toolProfile
    ) {
    }

    private record LocalTargetScan(
            int radius,
            int scannedLoadedBlocks,
            int configuredTargetCount,
            int diamondCount,
            Map<String, Integer> counts,
            String nearestDescription,
            List<BlockPos> targetPositions
    ) {
    }

    private record TargetHit(
            String id,
            BlockPos position,
            double distance,
            boolean configured,
            boolean diamond
    ) {
        private String description() {
            return id
                    + "@"
                    + position.getX() + ","
                    + position.getY() + ","
                    + position.getZ()
                    + " d="
                    + String.format(Locale.ROOT, "%.2f", distance)
                    + " selected="
                    + configured
                    + " diamond="
                    + diamond;
        }
    }
}
