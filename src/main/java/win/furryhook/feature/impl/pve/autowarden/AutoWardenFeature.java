package win.furryhook.feature.impl.pve.autowarden;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.event.events.render.Render3DEvent;
import win.furryhook.feature.FeatureEnableRejectedException;
import win.furryhook.feature.impl.pve.PveManagerFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ButtonSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.PvpStateTracker;
import win.furryhook.pve.economy.AuctionPriceScanner;
import win.furryhook.pve.economy.EconomyItemText;
import win.furryhook.pve.economy.ServerUiText;
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.server.ServerAdapter;
import win.furryhook.pve.server.ServerAdapters;
import win.furryhook.pve.server.ServerProfile;
import win.furryhook.utils.FriendManager;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.text.ChatUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class AutoWardenFeature extends PveFeature implements MinecraftContext {
    private static final int COMMAND_SETTLE_TICKS = 80;
    private static final int ACTION_INTERVAL_TICKS = 4;
    private static final int OPEN_RETRY_TICKS = 30;
    private static final int INVENTORY_FULL_FREE_SLOTS = 2;
    private static final int ZONE_COLOR = 0xCC14FFFF;
    private static final float ZONE_LINE_WIDTH = 1.4F;

    /**
     * Defaults have to be inside {@link AutoWardenParsers#MIN_ANARCHY}..{@link
     * AutoWardenParsers#MAX_ANARCHY}, because {@link #validatePveEnable()} refuses the toggle
     * when either of them fails to parse. The old pair — {@code "0"} and {@code ""} — could not
     * pass that check under any circumstances, so the feature shipped in a state where turning it
     * on was guaranteed to bounce until both fields were filled in by hand.
     */
    public final TextSetting homeAnarchy = register(new TextSetting(
            "Home Anarchy", "101", 3
    ));
    public final TextSetting lootAnarchies = register(new TextSetting(
            "Loot Anarchies", "102, 103, 104", 64
    ));
    public final NumberSetting maxChestWait = register(new NumberSetting(
            "Max Chest Wait", 900.0D, 0.0D, 7_200.0D, 10.0D, " s"
    ));
    public final NumberSetting minLootPerCycle = register(new NumberSetting(
            "Min Loot", 1.0D, 1.0D, 36.0D, 1.0D, " items"
    ));
    public final NumberSetting homeWaitThreshold = register(new NumberSetting(
            "Home Wait Threshold", 180.0D, 0.0D, 3_600.0D, 10.0D, " s"
    ));
    public final NumberSetting returnBuffer = register(new NumberSetting(
            "Return Buffer", 12.0D, 1.0D, 120.0D, 1.0D, " s"
    ));
    public final NumberSetting preOpenSeconds = register(new NumberSetting(
            "Before Opening", 2.0D, 0.0D, 20.0D, 1.0D, " s"
    ));
    public final NumberSetting chestGrace = register(new NumberSetting(
            "Chest Open Delay", 1.0D, 0.0D, 10.0D, 0.5D, " s"
    ));
    public final NumberSetting teleportCooldown = register(new NumberSetting(
            "Teleport Cooldown", 8.0D, 1.0D, 30.0D, 1.0D, " s"
    ));

    public final BooleanSetting needInvisibility = register(new BooleanSetting(
            "Require Invisibility", true
    ));
    public final BooleanSetting needFood = register(new BooleanSetting(
            "Require Food", true
    ));
    public final BooleanSetting needSpeed = register(new BooleanSetting(
            "Require Speed", false
    ));
    public final NumberSetting minInvisibility = register(new NumberSetting(
            "Min Invisibility", 2.0D, 0.0D, 64.0D, 1.0D, ""
    ).visibleWhen(this.needInvisibility::getValue));
    public final NumberSetting minFood = register(new NumberSetting(
            "Min Food", 32.0D, 0.0D, 256.0D, 1.0D, ""
    ).visibleWhen(this.needFood::getValue));
    public final NumberSetting minSpeed = register(new NumberSetting(
            "Min Speed", 1.0D, 0.0D, 64.0D, 1.0D, ""
    ).visibleWhen(this.needSpeed::getValue));

    public final NumberSetting homeScanRadius = register(new NumberSetting(
            "Home Scan Radius", 24.0D, 4.0D, 48.0D, 1.0D, " blocks"
    ));
    public final TextSetting restockSign = register(new TextSetting(
            "Restock Sign", "", 64
    ));
    public final BooleanSetting avoidCrowded = register(new BooleanSetting(
            "Avoid Crowded Chests", true
    ));
    public final NumberSetting crowdRadius = register(new NumberSetting(
            "Crowd Radius", 12.0D, 0.0D, 64.0D, 1.0D, " blocks"
    ));
    public final NumberSetting crowdPenalty = register(new NumberSetting(
            "Crowd Penalty", 20.0D, 0.0D, 500.0D, 1.0D, ""
    ));
    public final BooleanSetting fleeWarden = register(new BooleanSetting(
            "Flee From Warden", true
    ));
    public final BooleanSetting emptyHand = register(new BooleanSetting(
            "Keep Hand Empty", false
    ));

    public final BooleanSetting autoReconnect = register(new BooleanSetting(
            "Auto Reconnect", true
    ));
    public final NumberSetting autoReconnectDelay = register(new NumberSetting(
            "Reconnect Delay", 5.0D, 1.0D, 120.0D, 1.0D, " s"
    ).visibleWhen(this.autoReconnect::getValue));
    public final BooleanSetting autoReporter = register(new BooleanSetting(
            "Auto Reporter", false
    ));

    public final BooleanSetting scoutCities = register(new BooleanSetting(
            "Scout Warden Cities", true
    ));
    public final ButtonSetting scoutNow = register(new ButtonSetting(
            "Scout Now", "Scout", this::printScoutRecommendation
    ).visibleWhen(this.scoutCities::getValue));
    public final BooleanSetting telegramNotifications = register(new BooleanSetting(
            "Telegram Notifications", false
    ));
    public final ButtonSetting telegramConnect = register(new ButtonSetting(
            "Connect Telegram", "Connect", this::connectTelegram
    ).visibleWhen(this.telegramNotifications::getValue));

    public final BooleanSetting autoSell = register(new BooleanSetting(
            "Auto Sell", false
    ));
    public final TextSetting sellSign = register(new TextSetting(
            "Sell Sign", "продажа", 64
    ).visibleWhen(this.autoSell::getValue));
    public final NumberSetting sellMarkup = register(new NumberSetting(
            "Sell Markup", 0.0D, -50.0D, 200.0D, 1.0D, "%"
    ).visibleWhen(this.autoSell::getValue));
    public final NumberSetting fallbackSellPrice = register(new NumberSetting(
            "Fallback Sell Price", 1_000.0D, 1.0D, 100_000_000.0D, 100.0D, ""
    ).visibleWhen(this.autoSell::getValue));
    public final NumberSetting investPercent = register(new NumberSetting(
            "Invest Into Clan", 100.0D, 0.0D, 100.0D, 1.0D, "%"
    ).visibleWhen(this.autoSell::getValue));
    public final NumberSetting sellMaxPages = register(new NumberSetting(
            "Pages To Parse", 3.0D, 1.0D, 10.0D, 1.0D, ""
    ).visibleWhen(this.autoSell::getValue));

    public final BooleanSetting showZone = register(new BooleanSetting(
            "Show Warden Zone", true
    ));
    public final BooleanSetting showStats = register(new BooleanSetting(
            "Show Statistics", true
    ));

    private final AutoWardenWorkflow workflow = new AutoWardenWorkflow();
    private final AnarchyRotation rotation = new AnarchyRotation();
    private final AutoWardenStats stats = new AutoWardenStats();
    private final WardenChestScanner scanner = new WardenChestScanner();
    private final BaritoneNavigator navigator = BaritoneNavigator.INSTANCE;
    private final Set<String> reportedAttackers = new HashSet<>();

    private FunTimeWardenContract contract;
    private TelegramNotifier telegram;
    private List<Integer> configuredLootAnarchies = List.of();
    private int configuredHomeAnarchy = -1;
    private int currentAnarchy = -1;
    private int targetAnarchy = -1;
    private long tick;
    private long phaseActionTick;
    private long reconnectAtMillis;
    private long combatHoldUntilMillis;
    private long lastContainerOpenTick;
    private long lastScoutTick;
    private long storageRetryUntil;
    private boolean phaseActionStarted;
    private int phaseSubstep;
    private boolean navigationActive;
    private boolean reconnectAttempted;
    private boolean resourcesHeld;
    private boolean wasDead;
    private String lastAttacker;
    private ServerData reconnectServer;
    private BlockPos cityCenter;
    private BlockPos targetChest;
    private WardenChestScanner.ChestObservation targetObservation;
    private BlockPos storageCenter;
    private BlockPos activeStorageChest;
    private int patrolIndex;
    private int cycleLoot;
    private int depositedThisCycle;
    private int sellStep;
    private int sellSourceSlot = -1;
    private ItemStack sellStack = ItemStack.EMPTY;
    private long sellPrice;
    private long bestAuctionPrice;
    private int sellPagesScanned;

    public AutoWardenFeature() {
        super(
                "AutoWarden",
                "Automates Warden-city chest routes, storage, supplies and selling",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.BOT,
                AutomationResource.MOVEMENT,
                AutomationResource.ROTATION,
                AutomationResource.INVENTORY,
                AutomationResource.SCREEN,
                AutomationResource.CHAT,
                AutomationResource.NAVIGATION
        );
    }

    @Override
    protected void validatePveEnable() {
        ServerAdapter adapter = ServerAdapters.current();
        this.contract = new FunTimeWardenContract(adapter);
        if (!this.contract.supported()) {
            throw new FeatureEnableRejectedException(
                    "AutoWarden requires a FunTime server profile"
            );
        }
        this.configuredHomeAnarchy = AutoWardenParsers
                .parseHomeAnarchy(this.homeAnarchy.getValue())
                .orElse(-1);
        this.configuredLootAnarchies = AutoWardenParsers.parseLootAnarchies(
                this.lootAnarchies.getValue(),
                this.configuredHomeAnarchy
        );
        if (this.configuredHomeAnarchy < 0 || this.configuredLootAnarchies.isEmpty()) {
            throw new FeatureEnableRejectedException(
                    "configure one home anarchy and at least one loot anarchy"
            );
        }
        if (!this.navigator.isAvailable()) {
            throw new FeatureEnableRejectedException("Baritone is unavailable");
        }
    }

    @Override
    protected void onPveEnable() {
        Minecraft client = Minecraft.getInstance();
        resetRuntime();
        this.resourcesHeld = true;
        this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                NavigationOptions.walking()
        ));
        this.navigationActive = true;
        this.stats.start(System.currentTimeMillis());
        rememberServer(client);
        if (this.telegramNotifications.getValue()) {
            ensureTelegram().reloadAndVerify();
        }
    }

    @Override
    protected void onPveDisable() {
        cleanupRuntime(true);
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        this.resourcesHeld = false;
        endNavigation();
        closeOwnedContainer(mc.player);
        if ((reason == PveAutomationCoordinator.RevocationReason.DISCONNECT
                || reason == PveAutomationCoordinator.RevocationReason.WORLD_CHANGE)
                && this.autoReconnect.getValue()
                && this.reconnectServer != null) {
            scheduleReconnect();
        }
    }

    @Override
    protected boolean disableAfterRevocation(PveAutomationCoordinator.RevocationReason reason) {
        boolean reconnectTransition =
                reason == PveAutomationCoordinator.RevocationReason.DISCONNECT
                        || reason == PveAutomationCoordinator.RevocationReason.WORLD_CHANGE;
        return !reconnectTransition
                || !this.autoReconnect.getValue()
                || this.reconnectServer == null;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        rememberServer(client);
        if (client.player == null || client.level == null || client.gameMode == null) {
            tickReconnect(client);
            return;
        }
        if (!ensureResources()) {
            return;
        }

        LocalPlayer player = client.player;
        this.tick++;
        updateCurrentAnarchy();
        updateSupplies(player);
        if (this.scoutCities.getValue() && this.tick - this.lastScoutTick >= 6_000L) {
            printScoutRecommendation();
        }
        handleDeath(player);
        if (player.isDeadOrDying()) {
            return;
        }
        if (this.emptyHand.getValue()) {
            keepSafeHand(player);
        }

        if (dangerNearby(player, client.level)) {
            this.workflow.dangerDetected(this.tick);
        } else if (this.workflow.phase() == AutoWardenWorkflow.Phase.PVP_HIDE
                && System.currentTimeMillis() >= this.combatHoldUntilMillis
                && !PvpStateTracker.INSTANCE.isActive()) {
            this.workflow.dangerCleared(AutoWardenInventory.carryingValuables(player), this.tick);
            phaseChanged();
        }

        long timeoutOverride = this.workflow.phase() == AutoWardenWorkflow.Phase.HOME_WAIT
                ? Math.round((this.maxChestWait.getValue()
                + this.teleportCooldown.getValue() * 3.0D) * 20.0D)
                : -1L;
        if (this.workflow.timedOut(this.tick, timeoutOverride)) {
            failCurrentPhase("Phase timed out");
        }
        dispatchPhase(player, client.level);
        updateStatsPhase();
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE
                || !(event.getPacket() instanceof ClientboundSystemChatPacket packet)) {
            return;
        }
        String text = packet.content().getString();
        AutoWardenParsers.parseAnarchy(text).ifPresent(value -> this.currentAnarchy = value);
        AutoWardenParsers.parseCombatHoldMillis(text).ifPresent(value -> {
            this.combatHoldUntilMillis = Math.max(
                    this.combatHoldUntilMillis,
                    System.currentTimeMillis() + value
            );
            PvpStateTracker.INSTANCE.markCombatFor(value);
        });
        AutoWardenParsers.parseAttacker(text).ifPresent(attacker -> this.lastAttacker = attacker);
        if (AutoWardenParsers.indicatesFullAnarchy(text) && this.targetAnarchy >= 0) {
            this.rotation.avoidFor(this.targetAnarchy, 120_000L, System.currentTimeMillis());
            this.workflow.rotateAnarchy(this.tick);
            phaseChanged();
        }
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        rememberServer(event.getClient());
        if (this.autoReconnect.getValue() && this.reconnectServer != null) {
            scheduleReconnect();
        }
    }

    @EventTarget
    public void onWorldJoin(WorldJoinEvent event) {
        this.reconnectAtMillis = 0L;
        this.reconnectAttempted = false;
        if (!isEnabled()) {
            return;
        }
        if (ensureResources()) {
            this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                    NavigationOptions.walking()
            ));
            this.navigationActive = true;
            this.workflow.move(
                    AutoWardenWorkflow.Phase.ENSURE_ANARCHY,
                    "Connection restored",
                    this.tick
            );
            phaseChanged();
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.showStats.getValue()) {
            return;
        }
        AutoWardenStats.Snapshot snapshot = this.stats.snapshot(System.currentTimeMillis());
        float x = 12.0F;
        float y = 92.0F;
        for (var entry : snapshot.widgetData().entrySet()) {
            Render2DUtil.text(x, y, 10.0F, entry.getKey() + ": " + entry.getValue())
                    .style(UiFontStyle.MEDIUM)
                    .color(Theme.Colors.TEXT_TEXT)
                    .outline(0xB0000000, 0.7F)
                    .draw();
            y += 12.0F;
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.showZone.getValue() || mc.level == null || mc.levelRenderer == null) {
            return;
        }
        SimpleGizmoCollector collector = new SimpleGizmoCollector();
        try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(collector)) {
            for (Warden warden : mc.level.getEntitiesOfClass(
                    Warden.class,
                    mc.player == null
                            ? new AABB(BlockPos.ZERO)
                            : mc.player.getBoundingBox().inflate(96.0D)
            )) {
                Gizmos.addGizmo(new ZoneGizmo(
                        warden.getBoundingBox().inflate(16.0D, 4.0D, 16.0D)
                )).setAlwaysOnTop();
            }
        }
        event.getClient().levelRenderer.addMainThreadGizmos(collector.drainGizmos());
    }

    public AutoWardenWorkflow.Phase getPhase() {
        return this.workflow.phase();
    }

    public AutoWardenStats.Snapshot getStats() {
        return this.stats.snapshot(System.currentTimeMillis());
    }

    public int activeLootAnarchy() {
        return this.targetAnarchy;
    }

    public boolean isOnLootAnarchy() {
        return this.configuredLootAnarchies.contains(this.currentAnarchy);
    }

    public boolean managesHotbar() {
        return this.workflow.phase() != AutoWardenWorkflow.Phase.IDLE;
    }

    public boolean stocksSpeed() {
        return this.needSpeed.getValue();
    }

    private void dispatchPhase(LocalPlayer player, ClientLevel level) {
        switch (this.workflow.phase()) {
            case IDLE -> tickIdle(player);
            case ENSURE_ANARCHY -> tickEnsureAnarchy(player);
            case HOME_TO_WARDEN_CITY -> tickHomeToCity(player);
            case SEARCH_CHEST -> tickSearchChest(player, level);
            case PATROL_CITY -> tickPatrol(player, level);
            case MOVE_TO_CHEST -> tickMoveToChest(player);
            case HOME_WAIT -> tickHomeWait(player);
            case RETURN_TO_CITY -> tickReturnToCity(player);
            case OPEN_AND_LOOT -> tickOpenAndLoot(player);
            case PVP_HIDE -> tickHide(player, level);
            case GO_TO_STORAGE -> tickGoStorage(player);
            case DEPOSIT_LOOT -> tickDeposit(player, level);
            case RESTOCK -> tickRestock(player, level);
            case SELL_ITEMS -> tickSell(player, level);
        }
    }

    private void tickIdle(LocalPlayer player) {
        if (this.tick < this.storageRetryUntil) {
            return;
        }
        boolean needsStorage = AutoWardenInventory.carryingValuables(player)
                || suppliesMissing(player);
        if (this.workflow.start(true, needsStorage, this.tick)) {
            phaseChanged();
        }
    }

    private void tickEnsureAnarchy(LocalPlayer player) {
        if (!this.phaseActionStarted) {
            this.targetAnarchy = this.rotation.pickNext(
                    this.configuredLootAnarchies,
                    this.crowdPenalty.getValue(),
                    System.currentTimeMillis()
            );
            if (this.targetAnarchy < 0) {
                failCurrentPhase("No loot anarchy is available");
                return;
            }
            this.stats.activeAnarchy(this.targetAnarchy);
            if (this.currentAnarchy != this.targetAnarchy) {
                this.contract.switchAnarchy(this.targetAnarchy)
                        .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
            }
            startPhaseAction();
            return;
        }
        if (this.currentAnarchy == this.targetAnarchy
                || phaseActionElapsed(commandSettleTicks())) {
            this.currentAnarchy = this.targetAnarchy;
            this.workflow.anarchyReady(this.tick);
            phaseChanged();
        }
    }

    private void tickHomeToCity(LocalPlayer player) {
        if (suppliesMissing(player)) {
            this.workflow.requestStorage("Supplies are below the configured minimum", this.tick);
            phaseChanged();
            return;
        }
        if (!this.phaseActionStarted) {
            configuredHomeCommand().ifPresent(
                    command -> this.contractAdapter().sendCommand(player, command)
            );
            startPhaseAction();
            return;
        }
        if (phaseActionElapsed(commandSettleTicks())) {
            this.cityCenter = player.blockPosition();
            this.workflow.cityReady(this.tick);
            phaseChanged();
        }
    }

    private void tickSearchChest(LocalPlayer player, ClientLevel level) {
        long now = System.currentTimeMillis();
        List<WardenChestScanner.ChestObservation> candidates = this.scanner.scanLootChests(
                level,
                player,
                64,
                this.crowdRadius.getValue().intValue(),
                now
        );
        Optional<WardenChestScanner.ChestObservation> selected = this.scanner.selectBest(
                candidates,
                player.position(),
                this.maxChestWait.getValue().intValue(),
                this.avoidCrowded.getValue(),
                this.crowdPenalty.getValue(),
                now
        );
        if (selected.isEmpty()) {
            this.workflow.noChestFound(this.tick);
            phaseChanged();
            return;
        }
        selectChest(selected.get());
        int remaining = selected.get().remainingSeconds(now);
        this.workflow.chestSelected(
                remaining,
                this.homeWaitThreshold.getValue().intValue(),
                this.tick
        );
        phaseChanged();
    }

    private void tickPatrol(LocalPlayer player, ClientLevel level) {
        long now = System.currentTimeMillis();
        Optional<WardenChestScanner.ChestObservation> selected = this.scanner.selectBest(
                this.scanner.scanLootChests(
                        level,
                        player,
                        64,
                        this.crowdRadius.getValue().intValue(),
                        now
                ),
                player.position(),
                this.maxChestWait.getValue().intValue(),
                this.avoidCrowded.getValue(),
                this.crowdPenalty.getValue(),
                now
        );
        if (selected.isPresent()) {
            selectChest(selected.get());
            this.workflow.patrolFoundChest(this.tick);
            phaseChanged();
            return;
        }
        if (!this.navigator.isPathing() || phaseActionElapsed(200L)) {
            BlockPos center = this.cityCenter == null ? player.blockPosition() : this.cityCenter;
            int[][] offsets = {{24, 0}, {0, 24}, {-24, 0}, {0, -24}, {16, 16}, {-16, 16}, {-16, -16}, {16, -16}};
            int[] offset = offsets[Math.floorMod(this.patrolIndex++, offsets.length)];
            pathTo(center.offset(offset[0], 0, offset[1]), 3);
            startPhaseAction();
        }
    }

    private void tickMoveToChest(LocalPlayer player) {
        if (this.targetChest == null) {
            this.workflow.continueSearching(this.tick);
            phaseChanged();
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(this.targetChest)) <= 16.0D) {
            cancelNavigation();
            this.workflow.chestReached(this.tick);
            phaseChanged();
            return;
        }
        if (!this.navigator.isPathing()) {
            pathTo(this.targetChest, 2);
        }
    }

    private void tickHomeWait(LocalPlayer player) {
        if (!this.phaseActionStarted) {
            if (this.currentAnarchy != this.configuredHomeAnarchy) {
                this.contract.switchAnarchy(this.configuredHomeAnarchy)
                        .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
                this.phaseSubstep = 0;
            } else {
                configuredHomeCommand().ifPresent(
                        command -> this.contractAdapter().sendCommand(player, command)
                );
                this.phaseSubstep = 1;
            }
            startPhaseAction();
            return;
        }
        if (this.phaseSubstep == 0 && phaseActionElapsed(commandSettleTicks())) {
            this.currentAnarchy = this.configuredHomeAnarchy;
            configuredHomeCommand().ifPresent(
                    command -> this.contractAdapter().sendCommand(player, command)
            );
            this.phaseSubstep = 1;
            this.phaseActionTick = this.tick;
            return;
        }
        if (this.phaseSubstep == 1 && !phaseActionElapsed(commandSettleTicks())) {
            return;
        }
        int remaining = this.targetObservation == null
                ? 0
                : this.targetObservation.remainingSeconds(System.currentTimeMillis());
        int returnAt = this.returnBuffer.getValue().intValue()
                + this.preOpenSeconds.getValue().intValue();
        this.stats.homeWaitSeconds(remaining);
        if (remaining <= returnAt) {
            this.workflow.returnWindowReached(this.tick);
            phaseChanged();
        }
    }

    private void tickReturnToCity(LocalPlayer player) {
        if (!this.phaseActionStarted) {
            this.contract.switchAnarchy(this.targetAnarchy)
                    .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
            startPhaseAction();
            return;
        }
        if (this.phaseSubstep == 0 && phaseActionElapsed(commandSettleTicks())) {
            configuredHomeCommand().ifPresent(
                    command -> this.contractAdapter().sendCommand(player, command)
            );
            this.currentAnarchy = this.targetAnarchy;
            this.phaseSubstep = 1;
            this.phaseActionTick = this.tick;
            return;
        }
        if (this.phaseSubstep == 1 && phaseActionElapsed(commandSettleTicks())) {
            this.workflow.move(
                    AutoWardenWorkflow.Phase.MOVE_TO_CHEST,
                    "Returned for chest opening",
                    this.tick
            );
            phaseChanged();
        }
    }

    private void tickOpenAndLoot(LocalPlayer player) {
        AbstractContainerMenu menu = currentForeignMenu(player);
        if (menu == null) {
            if (!phaseActionElapsed(Math.max(
                    OPEN_RETRY_TICKS,
                    Math.round(this.chestGrace.getValue().floatValue() * 20.0F)
            ))) {
                return;
            }
            if (this.targetChest == null || !openContainer(player, this.targetChest)) {
                this.scanner.markFailed(this.targetChest, System.currentTimeMillis());
                this.workflow.continueSearching(this.tick);
                phaseChanged();
                return;
            }
            startPhaseAction();
            return;
        }
        if (actionReady() && AutoWardenInventory.quickMoveFirstContainerItem(
                menu,
                AutoWardenInventory::isValuable
        )) {
            this.cycleLoot++;
            this.phaseActionTick = this.tick;
            return;
        }

        closeOwnedContainer(player);
        this.scanner.markLooted(this.targetChest);
        this.stats.chestLooted(this.cycleLoot);
        this.rotation.onChestLooted(this.targetAnarchy);
        notifyTelegram("Looted chest on anarchy " + this.targetAnarchy
                + ": " + this.cycleLoot + " items");
        boolean shouldStore = this.cycleLoot >= this.minLootPerCycle.getValue().intValue()
                || AutoWardenInventory.freeSlots(player) <= INVENTORY_FULL_FREE_SLOTS
                || suppliesMissing(player);
        if (shouldStore) {
            this.workflow.requestStorage("Loot or supplies require storage", this.tick);
        } else {
            this.workflow.continueSearching(this.tick);
        }
        phaseChanged();
    }

    private void tickHide(LocalPlayer player, ClientLevel level) {
        Optional<Vec3> danger = nearestDanger(player, level);
        if (danger.isEmpty()) {
            return;
        }
        Vec3 away = player.position().subtract(danger.get()).normalize();
        if (away.lengthSqr() < 1.0E-6D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }
        BlockPos escape = BlockPos.containing(player.position().add(away.scale(24.0D)));
        if (!this.navigator.isPathing() || phaseActionElapsed(100L)) {
            pathTo(escape, 3);
            startPhaseAction();
        }
    }

    private void tickGoStorage(LocalPlayer player) {
        if (!this.phaseActionStarted) {
            if (this.currentAnarchy != this.configuredHomeAnarchy) {
                this.contract.switchAnarchy(this.configuredHomeAnarchy)
                        .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
                this.phaseSubstep = 0;
            } else {
                configuredHomeCommand().ifPresent(
                        command -> this.contractAdapter().sendCommand(player, command)
                );
                this.phaseSubstep = 1;
            }
            startPhaseAction();
            return;
        }
        if (this.phaseSubstep == 0 && phaseActionElapsed(commandSettleTicks())) {
            this.currentAnarchy = this.configuredHomeAnarchy;
            configuredHomeCommand().ifPresent(
                    command -> this.contractAdapter().sendCommand(player, command)
            );
            this.phaseSubstep = 1;
            this.phaseActionTick = this.tick;
            return;
        }
        if (this.phaseSubstep == 1 && phaseActionElapsed(commandSettleTicks())) {
            this.storageCenter = player.blockPosition();
            this.workflow.storageReady(this.tick);
            phaseChanged();
        }
    }

    private void tickDeposit(LocalPlayer player, ClientLevel level) {
        List<WardenChestScanner.StorageChest> storage = scanStorage(level);
        WardenChestScanner.StorageChest deposit = storage.stream()
                .filter(chest -> chest.kind() == WardenChestScanner.StorageKind.DEPOSIT)
                .findFirst()
                .orElse(null);
        if (deposit == null) {
            delayStorageRetry("No unsigned deposit chest was found");
            return;
        }
        if (!ensureStorageOpen(player, deposit.position())) {
            return;
        }
        AbstractContainerMenu menu = currentForeignMenu(player);
        if (menu == null) {
            return;
        }
        if (actionReady() && AutoWardenInventory.quickMoveFirstPlayerItem(
                menu,
                AutoWardenInventory::isValuable
        )) {
            this.depositedThisCycle++;
            this.phaseActionTick = this.tick;
            return;
        }
        closeOwnedContainer(player);
        this.stats.stored(this.depositedThisCycle);
        this.rotation.onDeposited(this.targetAnarchy, this.depositedThisCycle);
        boolean needsRestock = suppliesMissing(player);
        this.workflow.depositFinished(needsRestock, this.autoSell.getValue(), this.tick);
        if (!needsRestock && !this.autoSell.getValue()) {
            recordCycleComplete();
        }
        phaseChanged();
    }

    private void tickRestock(LocalPlayer player, ClientLevel level) {
        if (!suppliesMissing(player)) {
            closeOwnedContainer(player);
            this.workflow.restockFinished(this.autoSell.getValue(), this.tick);
            if (!this.autoSell.getValue()) {
                recordCycleComplete();
            }
            phaseChanged();
            return;
        }
        WardenChestScanner.StorageChest chest = scanStorage(level).stream()
                .filter(value -> value.kind() == WardenChestScanner.StorageKind.RESTOCK)
                .findFirst()
                .orElse(null);
        if (chest == null) {
            delayStorageRetry("No signed supply chest was found");
            return;
        }
        if (!ensureStorageOpen(player, chest.position())) {
            return;
        }
        AbstractContainerMenu menu = currentForeignMenu(player);
        if (menu == null || !actionReady()) {
            return;
        }
        if (this.needInvisibility.getValue()
                && AutoWardenInventory.countInvisibility(player) < this.minInvisibility.getValue().intValue()
                && AutoWardenInventory.quickMoveFirstContainerItem(
                menu,
                AutoWardenInventory::isInvisibilityPotion
        )) {
            this.phaseActionTick = this.tick;
            return;
        }
        if (this.needFood.getValue()
                && AutoWardenInventory.countFood(player) < this.minFood.getValue().intValue()
                && AutoWardenInventory.quickMoveFirstContainerItem(
                menu,
                AutoWardenInventory::isRestockableFood
        )) {
            this.phaseActionTick = this.tick;
            return;
        }
        if (this.needSpeed.getValue()
                && AutoWardenInventory.countSpeed(player) < this.minSpeed.getValue().intValue()
                && AutoWardenInventory.quickMoveFirstContainerItem(
                menu,
                AutoWardenInventory::isSpeedPotion
        )) {
            this.phaseActionTick = this.tick;
            return;
        }
        delayStorageRetry("Supply chest does not contain the required stock");
    }

    private void tickSell(LocalPlayer player, ClientLevel level) {
        WardenChestScanner.StorageChest chest = scanStorage(level).stream()
                .filter(value -> value.kind() == WardenChestScanner.StorageKind.SELL)
                .findFirst()
                .orElse(null);
        if (chest == null) {
            completeCycle();
            return;
        }
        if (this.sellStep == 0) {
            if (!ensureStorageOpen(player, chest.position())) {
                return;
            }
            AbstractContainerMenu menu = currentForeignMenu(player);
            if (menu == null || !actionReady()) {
                return;
            }
            int slot = ContainerLootService.findFirst(menu, AutoWardenInventory::isValuable);
            if (slot < 0) {
                closeOwnedContainer(player);
                completeCycle();
                return;
            }
            this.sellStack = menu.getSlot(slot).getItem().copy();
            ContainerLootService.quickMoveFirst(menu, stack ->
                    ItemStack.isSameItemSameComponents(stack, this.sellStack));
            this.phaseActionTick = this.tick;
            this.sellStep = 1;
            return;
        }
        if (this.sellStep == 1) {
            closeOwnedContainer(player);
            this.sellSourceSlot = InventoryUtil.findPlayerMenuSlot(
                    player,
                    stack -> ItemStack.isSameItemSameComponents(stack, this.sellStack)
            );
            if (this.sellSourceSlot < 0) {
                resetSellOperation();
                return;
            }
            this.contract.auction().ifPresent(command -> this.contractAdapter().sendCommand(
                    player,
                    command + " " + itemQuery(this.sellStack)
            ));
            this.phaseActionTick = this.tick;
            this.sellStep = 2;
            return;
        }
        if (this.sellStep == 2) {
            AbstractContainerMenu menu = currentForeignMenu(player);
            if (menu == null || !actionReady()) {
                return;
            }
            AuctionPriceScanner.competitivePrice(
                    menu,
                    this.sellStack.getItem(),
                    this.sellStack.getHoverName().getString(),
                    this.sellStack.getCount()
            ).ifPresent(value -> this.bestAuctionPrice = this.bestAuctionPrice == 0L
                    ? value
                    : Math.min(this.bestAuctionPrice, value));
            this.sellPagesScanned++;
            if (this.sellPagesScanned < this.sellMaxPages.getValue().intValue()) {
                int nextPage = ContainerLootService.findFirst(
                        menu,
                        stack -> EconomyItemText.containsAny(
                                stack,
                                "next page",
                                "следующая страница",
                                "вперед",
                                "далее"
                        )
                );
                if (nextPage >= 0) {
                    InventoryUtil.clickSlot(
                            nextPage,
                            0,
                            net.minecraft.world.inventory.ContainerInput.PICKUP
                    );
                    this.phaseActionTick = this.tick;
                    return;
                }
            }
            long base = this.bestAuctionPrice > 0L
                    ? this.bestAuctionPrice
                    : this.fallbackSellPrice.getValue().longValue();
            this.sellPrice = Math.max(1L, Math.round(
                    base * (1.0D + this.sellMarkup.getValue() / 100.0D)
            ));
            closeOwnedContainer(player);
            selectSellStack(player);
            this.contract.sell(this.sellPrice)
                    .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
            long invest = Math.round(this.sellPrice * this.investPercent.getValue() / 100.0D);
            this.contract.invest(invest)
                    .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
            this.phaseActionTick = this.tick;
            this.sellStep = 3;
            return;
        }
        if (phaseActionElapsed(40L)) {
            resetSellOperation();
        }
    }

    private void completeCycle() {
        this.workflow.sellFinished(this.tick);
        recordCycleComplete();
        phaseChanged();
    }

    private void recordCycleComplete() {
        this.stats.cycleComplete();
        notifyTelegram("AutoWarden cycle complete: " + this.stats.snapshot(
                System.currentTimeMillis()
        ).widgetData());
        this.scanner.clearLootedCycle();
        this.cycleLoot = 0;
        this.depositedThisCycle = 0;
    }

    private void selectChest(WardenChestScanner.ChestObservation observation) {
        this.targetObservation = observation;
        this.targetChest = observation.position();
        this.stats.target(
                observation.position().getX() + ", "
                        + observation.position().getY() + ", "
                        + observation.position().getZ()
        );
    }

    private List<WardenChestScanner.StorageChest> scanStorage(ClientLevel level) {
        BlockPos center = this.storageCenter == null
                ? mc.player.blockPosition()
                : this.storageCenter;
        return this.scanner.scanStorage(
                level,
                center,
                this.homeScanRadius.getValue().intValue(),
                this.restockSign.getValue(),
                this.sellSign.getValue()
        );
    }

    private boolean ensureStorageOpen(LocalPlayer player, BlockPos position) {
        AbstractContainerMenu menu = currentForeignMenu(player);
        if (menu != null && position.equals(this.activeStorageChest)) {
            return true;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(position)) > 16.0D) {
            if (!this.navigator.isPathing()) {
                pathTo(position, 2);
            }
            return false;
        }
        cancelNavigation();
        if (this.tick - this.lastContainerOpenTick < OPEN_RETRY_TICKS) {
            return false;
        }
        this.activeStorageChest = position;
        this.lastContainerOpenTick = this.tick;
        return openContainer(player, position);
    }

    private boolean openContainer(LocalPlayer player, BlockPos position) {
        if (mc.gameMode == null || position == null) {
            return false;
        }
        Vec3 directionVector = player.getEyePosition().subtract(Vec3.atCenterOf(position));
        Direction direction = Direction.getApproximateNearest(
                (float) directionVector.x,
                (float) directionVector.y,
                (float) directionVector.z
        );
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(position),
                direction,
                position,
                false
        );
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private AbstractContainerMenu currentForeignMenu(LocalPlayer player) {
        return player != null
                && player.containerMenu != null
                && player.containerMenu != player.inventoryMenu
                ? player.containerMenu
                : null;
    }

    private void closeOwnedContainer(LocalPlayer player) {
        if (player != null && currentForeignMenu(player) != null) {
            player.closeContainer();
        }
        this.activeStorageChest = null;
    }

    private boolean dangerNearby(LocalPlayer player, ClientLevel level) {
        Optional<Vec3> danger = nearestDanger(player, level);
        if (danger.isPresent()) {
            this.combatHoldUntilMillis = Math.max(
                    this.combatHoldUntilMillis,
                    System.currentTimeMillis() + 10_000L
            );
            return true;
        }
        return PvpStateTracker.INSTANCE.isActive()
                || System.currentTimeMillis() < this.combatHoldUntilMillis;
    }

    private Optional<Vec3> nearestDanger(LocalPlayer player, ClientLevel level) {
        List<Vec3> positions = new ArrayList<>();
        if (this.fleeWarden.getValue()) {
            for (Warden warden : level.getEntitiesOfClass(
                    Warden.class,
                    player.getBoundingBox().inflate(32.0D)
            )) {
                positions.add(warden.position());
            }
        }
        for (Player other : level.players()) {
            if (other == player
                    || !other.isAlive()
                    || other.isCreative()
                    || other.isSpectator()
                    || FriendManager.INSTANCE.isFriend(other.getGameProfile().name())) {
                continue;
            }
            if (player.distanceToSqr(other) <= 16.0D * 16.0D) {
                positions.add(other.position());
            }
        }
        return positions.stream().min(Comparator.comparingDouble(player.position()::distanceToSqr));
    }

    private boolean suppliesMissing(LocalPlayer player) {
        return this.needInvisibility.getValue()
                && AutoWardenInventory.countInvisibility(player) < this.minInvisibility.getValue().intValue()
                || this.needFood.getValue()
                && AutoWardenInventory.countFood(player) < this.minFood.getValue().intValue()
                || this.needSpeed.getValue()
                && AutoWardenInventory.countSpeed(player) < this.minSpeed.getValue().intValue();
    }

    private void updateSupplies(LocalPlayer player) {
        this.stats.supplies(
                AutoWardenInventory.countInvisibility(player),
                AutoWardenInventory.countFood(player),
                AutoWardenInventory.countSpeed(player)
        );
    }

    private void handleDeath(LocalPlayer player) {
        boolean dead = player.isDeadOrDying();
        if (dead && !this.wasDead) {
            this.wasDead = true;
            this.stats.died();
            this.rotation.onDeath(this.targetAnarchy);
            notifyTelegram("AutoWarden died on anarchy " + this.targetAnarchy);
            if (this.autoReporter.getValue()
                    && this.lastAttacker != null
                    && this.reportedAttackers.add(this.lastAttacker)) {
                this.contract.report(this.lastAttacker)
                        .ifPresent(command -> this.contractAdapter().sendCommand(player, command));
            }
        } else if (!dead) {
            this.wasDead = false;
        }
    }

    private void keepSafeHand(LocalPlayer player) {
        if (player.isUsingItem() || currentForeignMenu(player) != null) {
            return;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (player.getMainHandItem().isEmpty()) {
            return;
        }
        int safe = AutoWardenInventory.findEmptyHotbarSlot(player, selected);
        if (safe >= 0) {
            player.getInventory().setSelectedSlot(safe);
        }
    }

    private void selectSellStack(LocalPlayer player) {
        if (this.sellSourceSlot < 0) {
            return;
        }
        int selected = player.getInventory().getSelectedSlot();
        int selectedMenu = 36 + selected;
        if (this.sellSourceSlot >= 36 && this.sellSourceSlot <= 44) {
            player.getInventory().setSelectedSlot(this.sellSourceSlot - 36);
        } else if (this.sellSourceSlot != selectedMenu) {
            InventoryUtil.swapWithHotbar(this.sellSourceSlot, selected);
        }
    }

    private void resetSellOperation() {
        this.sellStep = 0;
        this.sellSourceSlot = -1;
        this.sellStack = ItemStack.EMPTY;
        this.sellPrice = 0L;
        this.bestAuctionPrice = 0L;
        this.sellPagesScanned = 0;
    }

    private static String itemQuery(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private void failCurrentPhase(String reason) {
        this.rotation.onFailedCycle(this.targetAnarchy);
        this.scanner.markFailed(this.targetChest, System.currentTimeMillis());
        cancelNavigation();
        closeOwnedContainer(mc.player);
        resetSellOperation();
        this.workflow.rotateAnarchy(this.tick);
        if (this.workflow.phase() != AutoWardenWorkflow.Phase.ENSURE_ANARCHY) {
            this.workflow.failSafe(reason, this.tick);
        }
        phaseChanged();
    }

    private void delayStorageRetry(String reason) {
        closeOwnedContainer(mc.player);
        this.storageRetryUntil = this.tick + 1_200L;
        this.workflow.failSafe(reason, this.tick);
        ChatUtil.error(reason);
        notifyTelegram(reason);
        phaseChanged();
    }

    private void pathTo(BlockPos position, int radius) {
        if (!this.navigationActive) {
            this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                    NavigationOptions.walking()
            ));
            this.navigationActive = true;
        }
        this.navigator.pathTo(position, radius);
    }

    private void cancelNavigation() {
        if (this.navigationActive) {
            this.navigator.cancel();
        }
    }

    private void endNavigation() {
        if (this.navigationActive) {
            this.navigator.end();
            this.navigationActive = false;
        }
    }

    private void startPhaseAction() {
        this.phaseActionStarted = true;
        this.phaseActionTick = this.tick;
    }

    private boolean phaseActionElapsed(long ticks) {
        return this.phaseActionStarted && this.tick - this.phaseActionTick >= Math.max(0L, ticks);
    }

    private boolean actionReady() {
        return this.tick - this.phaseActionTick >= ACTION_INTERVAL_TICKS;
    }

    private long commandSettleTicks() {
        return Math.max(
                COMMAND_SETTLE_TICKS,
                Math.round(this.teleportCooldown.getValue().floatValue() * 20.0F)
        );
    }

    private void phaseChanged() {
        this.phaseActionStarted = false;
        this.phaseSubstep = 0;
        this.phaseActionTick = this.tick;
        this.lastContainerOpenTick = Long.MIN_VALUE / 2L;
        this.activeStorageChest = null;
        this.stats.phase(this.workflow.phase(), this.workflow.lastReason());
    }

    private void updateStatsPhase() {
        this.stats.phase(this.workflow.phase(), this.workflow.lastReason());
    }

    private void updateCurrentAnarchy() {
        OptionalInt parsed = AutoWardenParsers.parseAnarchy(ServerUiText.tabHeader(mc));
        parsed.ifPresent(value -> this.currentAnarchy = value);
    }

    private void rememberServer(Minecraft client) {
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server != null && !server.isLan() && !server.isRealm()) {
            this.reconnectServer = server;
        }
    }

    private void scheduleReconnect() {
        this.reconnectAtMillis = System.currentTimeMillis()
                + Math.round(this.autoReconnectDelay.getValue() * 1000.0D);
        this.reconnectAttempted = false;
    }

    private void tickReconnect(Minecraft client) {
        if (!isEnabled()
                || !this.autoReconnect.getValue()
                || this.reconnectServer == null
                || this.reconnectAtMillis == 0L
                || this.reconnectAttempted
                || System.currentTimeMillis() < this.reconnectAtMillis) {
            return;
        }
        this.reconnectAttempted = true;
        ConnectScreen.startConnecting(
                new TitleScreen(),
                client,
                ServerAddress.parseString(this.reconnectServer.ip),
                this.reconnectServer,
                false,
                null
        );
    }

    private boolean ensureResources() {
        if (this.resourcesHeld) {
            return true;
        }
        this.resourcesHeld = PveAutomationCoordinator.INSTANCE.acquire(
                this,
                AutomationPriority.BOT,
                Set.of(
                        AutomationResource.MOVEMENT,
                        AutomationResource.ROTATION,
                        AutomationResource.INVENTORY,
                        AutomationResource.SCREEN,
                        AutomationResource.CHAT,
                        AutomationResource.NAVIGATION
                )
        );
        return this.resourcesHeld;
    }

    private void connectTelegram() {
        ensureTelegram().reloadAndVerify().thenAccept(status ->
                Minecraft.getInstance().execute(() -> ChatUtil.info(
                        "Telegram: " + status.name().toLowerCase(Locale.ROOT)
                )));
    }

    private void notifyTelegram(String message) {
        if (this.telegramNotifications.getValue()) {
            ensureTelegram().send(message);
        }
    }

    private TelegramNotifier ensureTelegram() {
        if (this.telegram == null) {
            this.telegram = new TelegramNotifier(Minecraft.getInstance().gameDirectory.toPath());
        }
        return this.telegram;
    }

    private void printScoutRecommendation() {
        if (this.configuredLootAnarchies.isEmpty()) {
            ChatUtil.error("Configure loot anarchies first");
            return;
        }
        long now = System.currentTimeMillis();
        int best = this.configuredLootAnarchies.stream()
                .max(Comparator.comparingDouble(value ->
                        this.rotation.score(value, this.crowdPenalty.getValue(), now)))
                .orElse(-1);
        ChatUtil.info(best < 0
                ? "No Warden city is currently available"
                : "Recommended Warden city: anarchy " + best);
        this.lastScoutTick = this.tick;
    }

    private Optional<String> configuredHomeCommand() {
        return this.contract.home(PveManagerFeature.INSTANCE.resolvedHomeName());
    }

    private ServerAdapter contractAdapter() {
        return ServerAdapters.current();
    }

    private void resetRuntime() {
        this.tick = 0L;
        this.phaseActionTick = 0L;
        this.reconnectAtMillis = 0L;
        this.combatHoldUntilMillis = 0L;
        this.lastContainerOpenTick = Long.MIN_VALUE / 2L;
        this.lastScoutTick = 0L;
        this.storageRetryUntil = 0L;
        this.phaseActionStarted = false;
        this.phaseSubstep = 0;
        this.reconnectAttempted = false;
        this.currentAnarchy = -1;
        this.targetAnarchy = -1;
        this.lastAttacker = null;
        this.cityCenter = null;
        this.targetChest = null;
        this.targetObservation = null;
        this.storageCenter = null;
        this.activeStorageChest = null;
        this.patrolIndex = 0;
        this.cycleLoot = 0;
        this.depositedThisCycle = 0;
        this.wasDead = false;
        this.reportedAttackers.clear();
        this.scanner.clearWorld();
        this.rotation.reset();
        this.workflow.reset(0L);
        resetSellOperation();
    }

    private void cleanupRuntime(boolean closeTelegram) {
        endNavigation();
        closeOwnedContainer(mc.player);
        this.resourcesHeld = false;
        this.workflow.reset(this.tick);
        this.scanner.clearWorld();
        resetSellOperation();
        if (closeTelegram && this.telegram != null) {
            this.telegram.close();
            this.telegram = null;
        }
    }

    private record ZoneGizmo(AABB box) implements Gizmo {
        @Override
        public void emit(GizmoPrimitives primitives, float alpha) {
            Vec3 a = new Vec3(this.box.minX, this.box.minY, this.box.minZ);
            Vec3 b = new Vec3(this.box.minX, this.box.minY, this.box.maxZ);
            Vec3 c = new Vec3(this.box.minX, this.box.maxY, this.box.minZ);
            Vec3 d = new Vec3(this.box.minX, this.box.maxY, this.box.maxZ);
            Vec3 e = new Vec3(this.box.maxX, this.box.minY, this.box.minZ);
            Vec3 f = new Vec3(this.box.maxX, this.box.minY, this.box.maxZ);
            Vec3 g = new Vec3(this.box.maxX, this.box.maxY, this.box.minZ);
            Vec3 h = new Vec3(this.box.maxX, this.box.maxY, this.box.maxZ);
            primitives.addLine(a, b, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(a, c, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(a, e, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(b, d, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(b, f, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(c, d, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(c, g, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(e, f, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(e, g, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(f, h, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(d, h, ZONE_COLOR, ZONE_LINE_WIDTH);
            primitives.addLine(g, h, ZONE_COLOR, ZONE_LINE_WIDTH);
        }
    }
}
