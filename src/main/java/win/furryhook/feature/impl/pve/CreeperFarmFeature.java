package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
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
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.server.ServerAdapters;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CreeperFarmFeature extends PveFeature implements MinecraftContext {
    public enum Phase {
        APPROACH,
        LOADING_CHUNKS,
        LOOTING,
        UNLOADING
    }

    enum UnloadStep {
        FIND,
        MOVE,
        OPEN
    }

    record FarmSignals(
            boolean insideRegion,
            boolean chunksLoaded,
            boolean shouldUnload,
            boolean unloadComplete,
            boolean timedOut
    ) {
    }

    public record FarmStats(
            long uptimeTicks,
            double money,
            int gunpowder,
            int kills,
            double incomePerHour,
            Phase phase,
            int recoveries
    ) {
    }

    private static final Pattern MONEY_NUMBER = Pattern.compile(
            "(?iu)(?:\\$|₽)\\s*([0-9][0-9\\s.,]*)([kкmм]?)"
                    + "|([0-9][0-9\\s.,]*)([kкmм]?)\\s*"
                    + "(?:\\$|₽|coins?|монет(?:а|ы|у)?|валют(?:а|ы)?)"
    );
    private static final int ACTION_INTERVAL_TICKS = 4;
    private static final int ATTACK_TIMEOUT_TICKS = 1_200;
    private static final int UNLOAD_RETRY_TICKS = 1_200;

    public final BooleanSetting unloadGunpowder = register(new BooleanSetting(
            "Unload Gunpowder", false
    ));
    public final ModeSetting unloadTarget = register(new ModeSetting(
            "Unload Target",
            "Chest",
            "Clan",
            "Ender Chest",
            "Chest",
            "None"
    ));
    public final TextSetting farmPosition = register(new TextSetting("Farm Position", "auto"));
    public final TextSetting regionMin = register(new TextSetting("Region Min", "auto"));
    public final TextSetting regionMax = register(new TextSetting("Region Max", "auto"));
    public final TextSetting storagePosition = register(new TextSetting("Storage Position", "auto"));
    public final TextSetting clanUnloadCommand = register(new TextSetting("Clan Unload Command", ""));
    public final NumberSetting regionRadius = register(new NumberSetting(
            "Region Radius", 32.0D, 8.0D, 128.0D, 4.0D, " blocks"
    ));
    public final NumberSetting regionHeight = register(new NumberSetting(
            "Region Height", 16.0D, 4.0D, 64.0D, 2.0D, " blocks"
    ));
    public final NumberSetting chunkStep = register(new NumberSetting(
            "Chunk Step", 1.0D, 1.0D, 4.0D, 1.0D, " chunks"
    ));
    public final NumberSetting chunkLoadTimeout = register(new NumberSetting(
            "Chunk Load Timeout", 60.0D, 10.0D, 180.0D, 5.0D, "s"
    ));
    public final NumberSetting unloadAtStacks = register(new NumberSetting(
            "Unload At", 16.0D, 1.0D, 36.0D, 1.0D, " stacks"
    ));
    public final NumberSetting attackRange = register(new NumberSetting(
            "Attack Range", 3.0D, 2.0D, 4.0D, 0.1D, " blocks"
    ));
    public final NumberSetting moneyPerGunpowder = register(new NumberSetting(
            "Money Per Gunpowder", 0.0D, 0.0D, 10_000.0D, 0.1D, ""
    ));

    private final PveStateMachine<Phase> state = new PveStateMachine<>(Phase.APPROACH);
    private final BaritoneNavigator navigator = BaritoneNavigator.INSTANCE;
    private final List<BlockPos> chunkWaypoints = new ArrayList<>();
    private final List<BlockPos> patrolWaypoints = new ArrayList<>();

    private long tick;
    private long lastActionTick;
    private long lastNavigationTick;
    private long lastProgressTick;
    private long unloadBackoffUntil;
    private BlockPos farmCenter;
    private BlockPos farmMin;
    private BlockPos farmMax;
    private BlockPos storage;
    private AABB farmBounds;
    private int chunkWaypointIndex;
    private int patrolWaypointIndex;
    private int previousGunpowder;
    private int gunpowderCollected;
    private int gunpowderUnloaded;
    private int kills;
    private int recoveries;
    private double moneyEarned;
    private Creeper target;
    private boolean targetAttacked;
    private boolean targetWasSwelling;
    private int targetAttacks;
    private boolean navigationActive;
    private boolean openedContainer;
    private boolean storageOpenRequested;
    private boolean unloadCommandSent;
    private UnloadStep unloadStep = UnloadStep.FIND;
    private float savedYaw;
    private float savedPitch;
    private boolean rotationSaved;

    public CreeperFarmFeature() {
        super(
                "CreeperFarm",
                "Loads a creeper farm, collects drops and unloads gunpowder",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.BOT,
                AutomationResource.MOVEMENT,
                AutomationResource.ROTATION,
                AutomationResource.INVENTORY,
                AutomationResource.SCREEN,
                AutomationResource.CHAT,
                AutomationResource.NAVIGATION,
                AutomationResource.COMBAT
        );
    }

    @Override
    protected void onPveEnable() {
        this.tick = 0L;
        this.lastActionTick = Long.MIN_VALUE / 2L;
        this.lastNavigationTick = Long.MIN_VALUE / 2L;
        this.lastProgressTick = 0L;
        this.unloadBackoffUntil = 0L;
        this.farmCenter = null;
        this.farmMin = null;
        this.farmMax = null;
        this.storage = null;
        this.farmBounds = null;
        this.chunkWaypointIndex = 0;
        this.patrolWaypointIndex = 0;
        this.previousGunpowder = mc.player == null ? 0 : countGunpowder(mc.player);
        this.gunpowderCollected = 0;
        this.gunpowderUnloaded = 0;
        this.kills = 0;
        this.recoveries = 0;
        this.moneyEarned = 0.0D;
        this.target = null;
        this.targetAttacked = false;
        this.targetWasSwelling = false;
        this.targetAttacks = 0;
        this.navigationActive = false;
        this.openedContainer = false;
        this.storageOpenRequested = false;
        this.unloadCommandSent = false;
        this.unloadStep = UnloadStep.FIND;
        this.rotationSaved = false;
        this.chunkWaypoints.clear();
        this.patrolWaypoints.clear();
        this.state.reset(0L);
        beginNavigation();
    }

    @Override
    protected void onPveDisable() {
        LocalPlayer player = mc.player;
        closeFeatureContainer(player);
        restoreRotation(player);
        endNavigation();
        clearTarget();
        this.chunkWaypoints.clear();
        this.patrolWaypoints.clear();
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        cancelNavigation();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        ClientLevel level = event.getClient().level;
        if (player == null || level == null || event.getClient().gameMode == null || !player.isAlive()) {
            return;
        }

        this.tick++;
        updateInventoryStats(player);
        updateTargetStats();
        switch (this.state.state()) {
            case APPROACH -> tickApproach(player);
            case LOADING_CHUNKS -> tickLoadingChunks(player);
            case LOOTING -> tickLooting(player, level);
            case UNLOADING -> tickUnloading(player, level);
        }
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE
                || !(event.getPacket() instanceof ClientboundSystemChatPacket packet)) {
            return;
        }
        OptionalDouble delta = parseMoneyDelta(packet.content().getString());
        if (delta.isPresent()) {
            this.moneyEarned += delta.getAsDouble();
        }
    }

    public Phase getPhase() {
        return this.state.state();
    }

    public BlockPos getRegionMin() {
        return this.farmMin;
    }

    public BlockPos getRegionMax() {
        return this.farmMax;
    }

    public FarmStats getStats() {
        double hours = this.tick / 72_000.0D;
        double incomePerHour = hours <= 0.0D ? 0.0D : this.moneyEarned / hours;
        return new FarmStats(
                this.tick,
                this.moneyEarned,
                this.gunpowderCollected,
                this.kills,
                incomePerHour,
                this.state.state(),
                this.recoveries
        );
    }

    public int getGunpowderUnloaded() {
        return this.gunpowderUnloaded;
    }

    private void tickApproach(LocalPlayer player) {
        resolveRegion(player);
        if (this.farmBounds == null || this.farmCenter == null) {
            recoverFromStall(player);
            return;
        }

        boolean inside = this.farmBounds.contains(player.position());
        boolean timedOut = this.tick - this.lastProgressTick > ATTACK_TIMEOUT_TICKS;
        Phase next = nextPhase(
                Phase.APPROACH,
                new FarmSignals(inside, false, false, false, timedOut)
        );
        if (next == Phase.LOADING_CHUNKS) {
            transition(next);
            return;
        }
        if (timedOut) {
            recoverFromStall(player);
            return;
        }
        navigateTo(this.farmCenter, 3);
    }

    private void tickLoadingChunks(LocalPlayer player) {
        if (this.chunkWaypoints.isEmpty()) {
            buildWaypoints();
        }
        long timeout = Math.max(1L, Math.round(this.chunkLoadTimeout.getValue() * 20.0D));
        boolean timedOut = this.state.ticksInState(this.tick) >= timeout;
        boolean complete = this.chunkWaypointIndex >= this.chunkWaypoints.size();
        Phase next = nextPhase(
                Phase.LOADING_CHUNKS,
                new FarmSignals(true, complete, false, false, timedOut)
        );
        if (next != Phase.LOADING_CHUNKS) {
            transition(next);
            return;
        }

        BlockPos waypoint = this.chunkWaypoints.get(this.chunkWaypointIndex);
        if (at(player, waypoint, 4.0D)) {
            this.chunkWaypointIndex++;
            markProgress();
            cancelNavigation();
            return;
        }
        navigateTo(waypoint, 3);
    }

    private void tickLooting(LocalPlayer player, ClientLevel level) {
        if (shouldUnload(player)) {
            transition(Phase.UNLOADING);
            return;
        }
        if (this.tick - this.lastProgressTick > ATTACK_TIMEOUT_TICKS) {
            recoverFromStall(player);
            return;
        }

        ItemEntity powder = nearestGunpowderEntity(level, player);
        if (powder != null) {
            clearTarget();
            if (powder.distanceToSqr(player) > 2.25D) {
                navigateTo(powder.blockPosition(), 1);
            } else {
                cancelNavigation();
            }
            return;
        }

        if (!validTarget(this.target)) {
            this.target = nearestCreeper(level, player);
            this.targetAttacked = false;
            this.targetWasSwelling = false;
            this.targetAttacks = 0;
        }
        if (this.target != null) {
            lootCreeper(player);
            return;
        }

        patrol(player);
    }

    private void lootCreeper(LocalPlayer player) {
        if (this.target == null) {
            return;
        }
        double distanceSquared = this.target.distanceToSqr(player);
        if (this.target.isIgnited() || this.target.getSwellDir() > 0) {
            this.targetWasSwelling = true;
            BlockPos retreat = farthestPatrolPoint(player.position());
            if (retreat != null) {
                navigateTo(retreat, 2);
            }
            return;
        }

        double range = this.attackRange.getValue();
        if (distanceSquared > range * range) {
            navigateTo(this.target.blockPosition(), Math.max(1, (int) Math.floor(range - 1.0D)));
            return;
        }
        cancelNavigation();
        if (!player.hasLineOfSight(this.target)
                || player.getAttackStrengthScale(0.0F) < 0.95F
                || !actionReady()) {
            return;
        }

        lookAt(player, this.target.getEyePosition());
        mc.gameMode.attack(player, this.target);
        player.swing(InteractionHand.MAIN_HAND);
        this.targetAttacked = true;
        this.targetAttacks++;
        this.lastActionTick = this.tick;
        markProgress();
        if (this.targetAttacks >= 20) {
            clearTarget();
        }
    }

    private void patrol(LocalPlayer player) {
        if (this.patrolWaypoints.isEmpty()) {
            buildWaypoints();
        }
        if (this.patrolWaypoints.isEmpty()) {
            recoverFromStall(player);
            return;
        }

        this.patrolWaypointIndex %= this.patrolWaypoints.size();
        BlockPos waypoint = this.patrolWaypoints.get(this.patrolWaypointIndex);
        if (at(player, waypoint, 4.0D)) {
            this.patrolWaypointIndex = (this.patrolWaypointIndex + 1) % this.patrolWaypoints.size();
            markProgress();
            cancelNavigation();
            return;
        }
        navigateTo(waypoint, 3);
    }

    private void tickUnloading(LocalPlayer player, ClientLevel level) {
        int powder = countGunpowder(player);
        if (powder <= 0) {
            closeFeatureContainer(player);
            transition(Phase.LOOTING);
            return;
        }
        if (this.unloadTarget.is("None") || !this.unloadGunpowder.getValue()) {
            this.unloadBackoffUntil = this.tick + UNLOAD_RETRY_TICKS;
            transition(Phase.LOOTING);
            return;
        }
        if (this.tick - this.lastProgressTick > 800L) {
            this.unloadBackoffUntil = this.tick + UNLOAD_RETRY_TICKS;
            recoverFromStall(player);
            return;
        }

        if (this.unloadTarget.is("Clan")) {
            tickClanUnload(player);
            return;
        }
        tickBlockStorageUnload(player, level);
    }

    private void tickClanUnload(LocalPlayer player) {
        String command = this.clanUnloadCommand.getValue().trim();
        if (command.isEmpty()) {
            this.unloadBackoffUntil = this.tick + UNLOAD_RETRY_TICKS;
            transition(Phase.LOOTING);
            return;
        }
        if (!this.unloadCommandSent) {
            ServerAdapters.current().sendCommand(player, command);
            this.unloadCommandSent = true;
            this.storageOpenRequested = true;
            this.lastActionTick = this.tick;
        }
        AbstractContainerMenu menu = currentStorageMenu(player);
        if (menu != null) {
            depositGunpowder(player, menu);
            return;
        }
        if (this.state.ticksInState(this.tick) > 240L) {
            this.unloadBackoffUntil = this.tick + UNLOAD_RETRY_TICKS;
            closeFeatureContainer(player);
            transition(Phase.LOOTING);
        }
    }

    private void tickBlockStorageUnload(LocalPlayer player, ClientLevel level) {
        switch (this.unloadStep) {
            case FIND -> {
                this.storage = resolveStorage(player, level);
                if (this.storage != null) {
                    this.unloadStep = UnloadStep.MOVE;
                    markProgress();
                } else if (this.state.ticksInState(this.tick) > 200L) {
                    this.unloadBackoffUntil = this.tick + UNLOAD_RETRY_TICKS;
                    transition(Phase.LOOTING);
                }
            }
            case MOVE -> {
                if (this.storage == null) {
                    this.unloadStep = UnloadStep.FIND;
                    return;
                }
                if (at(player, this.storage, 4.0D)) {
                    cancelNavigation();
                    this.unloadStep = UnloadStep.OPEN;
                    markProgress();
                } else {
                    navigateTo(this.storage, 2);
                }
            }
            case OPEN -> {
                AbstractContainerMenu menu = currentStorageMenu(player);
                if (menu != null) {
                    depositGunpowder(player, menu);
                    return;
                }
                if (this.storage == null || !at(player, this.storage, 4.0D)) {
                    this.unloadStep = UnloadStep.MOVE;
                    return;
                }
                if (actionReady()) {
                    openStorage(player, this.storage);
                }
            }
        }
    }

    private void depositGunpowder(LocalPlayer player, AbstractContainerMenu menu) {
        int slot = findPlayerContainerSlot(menu, stack -> stack.is(Items.GUNPOWDER));
        if (slot >= 0 && actionReady()) {
            InventoryUtil.quickMoveSlot(slot);
            this.lastActionTick = this.tick;
            return;
        }
        if (slot >= 0) {
            return;
        }
        closeFeatureContainer(player);
        transition(Phase.LOOTING);
    }

    private void openStorage(LocalPlayer player, BlockPos position) {
        lookAt(player, Vec3.atCenterOf(position));
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(position),
                Direction.UP,
                position,
                false
        );
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        this.storageOpenRequested = true;
        this.lastActionTick = this.tick;
    }

    private AbstractContainerMenu currentStorageMenu(LocalPlayer player) {
        if ((!this.storageOpenRequested && !this.openedContainer)
                || !InventoryUtil.isContainerScreenOpen()) {
            return null;
        }
        AbstractContainerMenu menu = InventoryUtil.getOpenMenu();
        if (menu == null || menu == player.inventoryMenu) {
            return null;
        }
        this.openedContainer = true;
        return menu;
    }

    private BlockPos resolveStorage(LocalPlayer player, ClientLevel level) {
        Optional<BlockPos> configured = PveCoordinateParser.parse(this.storagePosition.getValue());
        if (configured.isPresent()) {
            return configured.get();
        }

        BlockPos origin = this.farmCenter == null ? player.blockPosition() : this.farmCenter;
        boolean ender = this.unloadTarget.is("Ender Chest");
        BlockPos signed = findSignedStorage(level, origin, ender);
        return signed != null ? signed : findNearestStorage(level, player, origin, ender);
    }

    private BlockPos findSignedStorage(ClientLevel level, BlockPos origin, boolean ender) {
        int radius = Math.min(20, intValue(this.regionRadius));
        int yRadius = Math.min(8, intValue(this.regionHeight));
        for (BlockPos cursor : BlockPos.betweenClosed(
                origin.offset(-radius, -yRadius, -radius),
                origin.offset(radius, yRadius, radius))) {
            if (!(level.getBlockEntity(cursor) instanceof SignBlockEntity sign)) {
                continue;
            }
            List<String> lines = new ArrayList<>(8);
            for (int line = 0; line < 4; line++) {
                lines.add(sign.getFrontText().getMessage(line, false).getString());
                lines.add(sign.getBackText().getMessage(line, false).getString());
            }
            if (!matchesGunpowderLabel(lines)) {
                continue;
            }
            BlockPos storagePos = storageNear(level, cursor, 2, ender);
            if (storagePos != null) {
                return storagePos;
            }
        }
        return null;
    }

    private BlockPos findNearestStorage(ClientLevel level,
                                        LocalPlayer player,
                                        BlockPos origin,
                                        boolean ender) {
        int radius = Math.min(20, intValue(this.regionRadius));
        int yRadius = Math.min(8, intValue(this.regionHeight));
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos cursor : BlockPos.betweenClosed(
                origin.offset(-radius, -yRadius, -radius),
                origin.offset(radius, yRadius, radius))) {
            if (!isStorage(level.getBlockState(cursor), ender)) {
                continue;
            }
            double distance = player.position().distanceToSqr(Vec3.atCenterOf(cursor));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cursor.immutable();
            }
        }
        return best;
    }

    private static BlockPos storageNear(ClientLevel level,
                                        BlockPos sign,
                                        int radius,
                                        boolean ender) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos cursor : BlockPos.betweenClosed(
                sign.offset(-radius, -radius, -radius),
                sign.offset(radius, radius, radius))) {
            if (!isStorage(level.getBlockState(cursor), ender)) {
                continue;
            }
            double distance = cursor.distSqr(sign);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cursor.immutable();
            }
        }
        return best;
    }

    static boolean matchesGunpowderLabel(Iterable<String> lines) {
        for (String line : lines) {
            String normalized = line == null
                    ? ""
                    : line.replaceAll("§.", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]+", " ")
                    .trim();
            if (normalized.contains("gunpowder") || normalized.contains("порох")) {
                return true;
            }
        }
        return false;
    }

    private void resolveRegion(LocalPlayer player) {
        BlockPos configuredCenter = PveCoordinateParser.parse(this.farmPosition.getValue())
                .orElseGet(() -> this.farmCenter == null
                        ? player.blockPosition().immutable()
                        : this.farmCenter);
        Optional<BlockPos> configuredMin = PveCoordinateParser.parse(this.regionMin.getValue());
        Optional<BlockPos> configuredMax = PveCoordinateParser.parse(this.regionMax.getValue());

        this.farmCenter = configuredCenter;
        if (configuredMin.isPresent() && configuredMax.isPresent()) {
            BlockPos first = configuredMin.get();
            BlockPos second = configuredMax.get();
            this.farmMin = new BlockPos(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ())
            );
            this.farmMax = new BlockPos(
                    Math.max(first.getX(), second.getX()),
                    Math.max(first.getY(), second.getY()),
                    Math.max(first.getZ(), second.getZ())
            );
            this.farmCenter = new BlockPos(
                    (this.farmMin.getX() + this.farmMax.getX()) / 2,
                    (this.farmMin.getY() + this.farmMax.getY()) / 2,
                    (this.farmMin.getZ() + this.farmMax.getZ()) / 2
            );
        } else {
            int radius = intValue(this.regionRadius);
            int height = intValue(this.regionHeight);
            this.farmMin = this.farmCenter.offset(-radius, -height, -radius);
            this.farmMax = this.farmCenter.offset(radius, height, radius);
        }
        this.farmBounds = new AABB(
                this.farmMin.getX(),
                this.farmMin.getY(),
                this.farmMin.getZ(),
                this.farmMax.getX() + 1.0D,
                this.farmMax.getY() + 1.0D,
                this.farmMax.getZ() + 1.0D
        );
        if (this.chunkWaypoints.isEmpty()) {
            buildWaypoints();
        }
    }

    private void buildWaypoints() {
        this.chunkWaypoints.clear();
        this.patrolWaypoints.clear();
        if (this.farmMin == null || this.farmMax == null || this.farmCenter == null) {
            return;
        }

        int step = Math.max(16, intValue(this.chunkStep) * 16);
        int y = this.farmCenter.getY();
        for (int x = this.farmMin.getX(); x <= this.farmMax.getX(); x += step) {
            for (int z = this.farmMin.getZ(); z <= this.farmMax.getZ(); z += step) {
                this.chunkWaypoints.add(new BlockPos(x, y, z));
                if (this.chunkWaypoints.size() >= 81) {
                    break;
                }
            }
            if (this.chunkWaypoints.size() >= 81) {
                break;
            }
        }
        if (this.chunkWaypoints.stream().noneMatch(this.farmCenter::equals)) {
            this.chunkWaypoints.add(this.farmCenter);
        }

        this.patrolWaypoints.add(new BlockPos(this.farmMin.getX(), y, this.farmMin.getZ()));
        this.patrolWaypoints.add(new BlockPos(this.farmMax.getX(), y, this.farmMin.getZ()));
        this.patrolWaypoints.add(new BlockPos(this.farmMax.getX(), y, this.farmMax.getZ()));
        this.patrolWaypoints.add(new BlockPos(this.farmMin.getX(), y, this.farmMax.getZ()));
        this.patrolWaypoints.add(this.farmCenter);
    }

    private ItemEntity nearestGunpowderEntity(ClientLevel level, LocalPlayer player) {
        if (this.farmBounds == null) {
            return null;
        }
        ItemEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)
                    || !item.isAlive()
                    || !item.getItem().is(Items.GUNPOWDER)
                    || !this.farmBounds.contains(item.position())) {
                continue;
            }
            double distance = item.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    private Creeper nearestCreeper(ClientLevel level, LocalPlayer player) {
        return creepersSortedByDistance(level, player).stream().findFirst().orElse(null);
    }

    public List<Creeper> creepersSortedByDistance(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null || this.farmBounds == null) {
            return List.of();
        }
        List<Creeper> creepers = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Creeper creeper
                    && validTarget(creeper)
                    && this.farmBounds.contains(creeper.position())) {
                creepers.add(creeper);
            }
        }
        creepers.sort(Comparator.comparingDouble(creeper -> creeper.distanceToSqr(player)));
        return List.copyOf(creepers);
    }

    private boolean validTarget(Creeper creeper) {
        return creeper != null
                && creeper.isAlive()
                && !creeper.isRemoved()
                && this.farmBounds != null
                && this.farmBounds.contains(creeper.position());
    }

    private void updateTargetStats() {
        if (this.target == null || this.target.isAlive() && !this.target.isRemoved()) {
            return;
        }
        if (this.targetAttacked && !this.targetWasSwelling) {
            this.kills++;
        }
        clearTarget();
    }

    private void updateInventoryStats(LocalPlayer player) {
        int current = countGunpowder(player);
        int gained = Math.max(0, current - this.previousGunpowder);
        int unloaded = Math.max(0, this.previousGunpowder - current);
        if (gained > 0 && this.state.state() != Phase.UNLOADING) {
            this.gunpowderCollected += gained;
            this.moneyEarned += gained * this.moneyPerGunpowder.getValue();
            markProgress();
        }
        if (unloaded > 0 && this.state.state() == Phase.UNLOADING) {
            this.gunpowderUnloaded += unloaded;
            markProgress();
        }
        this.previousGunpowder = current;
    }

    private boolean shouldUnload(LocalPlayer player) {
        if (!this.unloadGunpowder.getValue()
                || this.unloadTarget.is("None")
                || this.tick < this.unloadBackoffUntil) {
            return false;
        }
        int powder = countGunpowder(player);
        int threshold = intValue(this.unloadAtStacks) * 64;
        return powder >= threshold || powder > 0 && freeSlots(player) <= 1;
    }

    private void recoverFromStall(LocalPlayer player) {
        cancelNavigation();
        closeFeatureContainer(player);
        clearTarget();
        this.recoveries++;
        this.lastProgressTick = this.tick;
        this.chunkWaypointIndex = 0;
        this.patrolWaypointIndex = 0;
        this.unloadStep = UnloadStep.FIND;
        this.unloadCommandSent = false;
        this.storage = null;
        if (this.state.state() == Phase.APPROACH) {
            this.state.reset(this.tick);
        } else {
            transition(Phase.APPROACH);
        }
    }

    private void transition(Phase next) {
        if (!this.state.transition(next, this.tick)) {
            return;
        }
        cancelNavigation();
        markProgress();
        if (next == Phase.LOADING_CHUNKS) {
            this.chunkWaypointIndex = 0;
        }
        if (next == Phase.UNLOADING) {
            this.unloadStep = UnloadStep.FIND;
            this.unloadCommandSent = false;
            this.storage = null;
        }
        if (next != Phase.UNLOADING) {
            closeFeatureContainer(mc.player);
        }
        if (next != Phase.LOOTING) {
            clearTarget();
        }
    }

    static Phase nextPhase(Phase phase, FarmSignals signals) {
        return switch (phase) {
            case APPROACH -> signals.insideRegion() ? Phase.LOADING_CHUNKS : Phase.APPROACH;
            case LOADING_CHUNKS -> signals.chunksLoaded() || signals.timedOut()
                    ? Phase.LOOTING
                    : Phase.LOADING_CHUNKS;
            case LOOTING -> signals.shouldUnload() ? Phase.UNLOADING : Phase.LOOTING;
            case UNLOADING -> signals.unloadComplete()
                    ? Phase.LOOTING
                    : signals.timedOut() ? Phase.APPROACH : Phase.UNLOADING;
        };
    }

    static OptionalDouble parseMoneyDelta(String message) {
        if (message == null) {
            return OptionalDouble.empty();
        }
        String normalized = message.replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
        boolean positiveContext = normalized.contains("+")
                || normalized.contains("earned")
                || normalized.contains("received")
                || normalized.contains("sold")
                || normalized.contains("заработ")
                || normalized.contains("получ")
                || normalized.contains("продан");
        boolean moneyContext = normalized.contains("$")
                || normalized.contains("₽")
                || normalized.contains("coin")
                || normalized.contains("монет")
                || normalized.contains("валют");
        if (!positiveContext || !moneyContext) {
            return OptionalDouble.empty();
        }

        Matcher matcher = MONEY_NUMBER.matcher(normalized);
        double best = -1.0D;
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String suffix = matcher.group(1) != null ? matcher.group(2) : matcher.group(4);
            OptionalDouble parsed = parseLocalizedNumber(token, suffix);
            if (parsed.isPresent()) {
                best = Math.max(best, parsed.getAsDouble());
            }
        }
        return best > 0.0D ? OptionalDouble.of(best) : OptionalDouble.empty();
    }

    private static OptionalDouble parseLocalizedNumber(String token, String suffix) {
        String compact = token.replace(" ", "");
        if (compact.isEmpty()) {
            return OptionalDouble.empty();
        }

        int comma = compact.lastIndexOf(',');
        int dot = compact.lastIndexOf('.');
        int separator = Math.max(comma, dot);
        String normalized;
        if (separator >= 0 && compact.length() - separator - 1 <= 2) {
            String integer = compact.substring(0, separator).replace(",", "").replace(".", "");
            String fraction = compact.substring(separator + 1);
            normalized = integer + "." + fraction;
        } else {
            normalized = compact.replace(",", "").replace(".", "");
        }

        try {
            double value = Double.parseDouble(normalized);
            if (suffix != null && (suffix.equalsIgnoreCase("k") || suffix.equalsIgnoreCase("к"))) {
                value *= 1_000.0D;
            } else if (suffix != null && (suffix.equalsIgnoreCase("m") || suffix.equalsIgnoreCase("м"))) {
                value *= 1_000_000.0D;
            }
            return value > 0.0D ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    private boolean beginNavigation() {
        if (!this.navigator.isAvailable()) {
            return false;
        }
        try {
            this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                    NavigationOptions.walking()
            ));
            this.navigationActive = true;
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            this.navigationActive = false;
            return false;
        }
    }

    private void navigateTo(BlockPos target, int radius) {
        if (target == null || !beginNavigation()) {
            return;
        }
        Optional<BlockPos> goal = this.navigator.currentGoal();
        boolean sameGoal = goal.isPresent() && goal.get().equals(target);
        if (!sameGoal || !this.navigator.isPathing() && this.tick - this.lastNavigationTick >= 40L) {
            try {
                this.navigator.pathTo(target, radius);
                this.lastNavigationTick = this.tick;
            } catch (RuntimeException | LinkageError ignored) {
                this.lastNavigationTick = this.tick;
            }
        }
    }

    private void cancelNavigation() {
        if (!this.navigationActive) {
            return;
        }
        try {
            this.navigator.cancel();
        } catch (RuntimeException | LinkageError ignored) {
            this.navigationActive = false;
        }
    }

    private void endNavigation() {
        if (!this.navigationActive) {
            return;
        }
        try {
            this.navigator.end();
        } catch (RuntimeException | LinkageError ignored) {
            this.navigator.cancel();
        } finally {
            this.navigationActive = false;
        }
    }

    private void closeFeatureContainer(LocalPlayer player) {
        if (this.openedContainer
                && player != null
                && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        this.openedContainer = false;
        this.storageOpenRequested = false;
    }

    private void clearTarget() {
        this.target = null;
        this.targetAttacked = false;
        this.targetWasSwelling = false;
        this.targetAttacks = 0;
    }

    private BlockPos farthestPatrolPoint(Vec3 position) {
        BlockPos farthest = null;
        double distance = Double.NEGATIVE_INFINITY;
        for (BlockPos waypoint : this.patrolWaypoints) {
            double candidate = position.distanceToSqr(Vec3.atCenterOf(waypoint));
            if (candidate > distance) {
                distance = candidate;
                farthest = waypoint;
            }
        }
        return farthest;
    }

    private boolean actionReady() {
        return this.tick - this.lastActionTick >= ACTION_INTERVAL_TICKS;
    }

    private void markProgress() {
        this.lastProgressTick = this.tick;
    }

    private void lookAt(LocalPlayer player, Vec3 targetPosition) {
        if (!PveManagerFeature.INSTANCE.rotate.getValue()) {
            return;
        }
        saveRotation(player);
        Vec3 delta = targetPosition.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        player.setYRot((float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F);
        player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)));
    }

    private void saveRotation(LocalPlayer player) {
        if (this.rotationSaved || player == null) {
            return;
        }
        this.savedYaw = player.getYRot();
        this.savedPitch = player.getXRot();
        this.rotationSaved = true;
    }

    private void restoreRotation(LocalPlayer player) {
        if (!this.rotationSaved || player == null) {
            return;
        }
        player.setYRot(this.savedYaw);
        player.setXRot(this.savedPitch);
        this.rotationSaved = false;
    }

    private static boolean isStorage(BlockState state, boolean ender) {
        return ender
                ? state.is(Blocks.ENDER_CHEST)
                : state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private static int findPlayerContainerSlot(AbstractContainerMenu menu,
                                               java.util.function.Predicate<ItemStack> predicate) {
        int start = ContainerLootService.containerSlotCount(menu);
        for (int slot = start; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private static int countGunpowder(LocalPlayer player) {
        int count = 0;
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (stack.is(Items.GUNPOWDER)) {
                count += stack.getCount();
            }
        }
        if (player.getOffhandItem().is(Items.GUNPOWDER)) {
            count += player.getOffhandItem().getCount();
        }
        return count;
    }

    private static int freeSlots(LocalPlayer player) {
        int free = 0;
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            if (player.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static boolean at(LocalPlayer player, BlockPos position, double radius) {
        return position != null
                && player.position().distanceToSqr(Vec3.atCenterOf(position)) <= radius * radius;
    }

    private static int intValue(NumberSetting setting) {
        return (int) Math.round(setting.getValue());
    }
}
