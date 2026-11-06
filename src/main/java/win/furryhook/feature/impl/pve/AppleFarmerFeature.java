package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.PveStateMachine;
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.server.ServerAdapter;
import win.furryhook.pve.server.ServerAdapters;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AppleFarmerFeature extends PveFeature implements MinecraftContext {
    public enum Phase {
        RETURN_HOME,
        FIND_FARM,
        APPROACH_FARM,
        PLANT,
        GROW,
        BREAK_LEAVES,
        BREAK_LOGS,
        DROP_JUNK,
        DEPOSIT_APPLES_FIND,
        DEPOSIT_APPLES_MOVE,
        DEPOSIT_APPLES_OPEN,
        BONES_FIND,
        BONES_MOVE,
        BONES_OPEN,
        CRAFT_BONE_MEAL,
        BUY_BOTTLES,
        REPAIR
    }

    enum StorageKind {
        APPLES,
        BONES
    }

    record FarmerSignals(
            boolean atFarm,
            boolean workRemaining,
            boolean hasResource,
            boolean hasJunk,
            boolean shouldDepositApples,
            boolean destinationKnown,
            boolean atDestination,
            boolean inventoryWorkRemaining,
            boolean shouldFetchBones,
            boolean hasBones,
            boolean needsRepair,
            boolean hasBottles,
            boolean canBuyBottles,
            boolean timedOut
    ) {
    }

    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static final int CRAFT_RESULT_SLOT = 0;
    private static final int CRAFT_INPUT_SLOT = 1;
    private static final int HOME_SETTLE_TICKS = 60;
    private static final int ACTION_INTERVAL_TICKS = 4;
    private static final int STORAGE_RETRY_TICKS = 1_200;
    private static final double INTERACT_DISTANCE_SQUARED = 20.25D;

    public final BooleanSetting takeBones = register(new BooleanSetting("Take Bones", false));
    public final BooleanSetting dropJunk = register(new BooleanSetting("Drop Junk", false));
    public final TextSetting farmPosition = register(new TextSetting("Farm Position", "auto"));
    public final TextSetting appleStorage = register(new TextSetting("Apple Storage", "auto"));
    public final TextSetting boneStorage = register(new TextSetting("Bone Storage", "auto"));
    public final BooleanSetting useHomeCommand = register(new BooleanSetting("Use Home Command", false));
    public final NumberSetting farmRadius = register(new NumberSetting(
            "Farm Radius", 8.0D, 3.0D, 24.0D, 1.0D, " blocks"
    ));
    public final NumberSetting verticalScan = register(new NumberSetting(
            "Vertical Scan", 10.0D, 3.0D, 24.0D, 1.0D, " blocks"
    ));
    public final NumberSetting treesPerCycle = register(new NumberSetting(
            "Trees Per Cycle", 4.0D, 1.0D, 32.0D, 1.0D, ""
    ));
    public final NumberSetting maxBlocksPerCycle = register(new NumberSetting(
            "Max Blocks Per Cycle", 192.0D, 16.0D, 1_024.0D, 16.0D, ""
    ));
    public final NumberSetting boneMealPerCycle = register(new NumberSetting(
            "Bone Meal Per Cycle", 48.0D, 1.0D, 256.0D, 1.0D, ""
    ));
    public final NumberSetting boneMealReserve = register(new NumberSetting(
            "Bone Meal Reserve", 16.0D, 0.0D, 256.0D, 1.0D, ""
    ));
    public final NumberSetting saplingReserve = register(new NumberSetting(
            "Sapling Reserve", 16.0D, 1.0D, 128.0D, 1.0D, ""
    ));
    public final NumberSetting depositAppleStacks = register(new NumberSetting(
            "Deposit Apple Stacks", 4.0D, 1.0D, 27.0D, 1.0D, " stacks"
    ));
    public final NumberSetting repairBelow = register(new NumberSetting(
            "Repair Below", 20.0D, 1.0D, 95.0D, 1.0D, "%"
    ));
    public final NumberSetting repairTo = register(new NumberSetting(
            "Repair To", 90.0D, 5.0D, 100.0D, 1.0D, "%"
    ));
    public final BooleanSetting buyXpBottles = register(new BooleanSetting("Buy XP Bottles", false));
    public final TextSetting xpBottleBuyCommand = register(new TextSetting("XP Bottle Buy Command", ""));

    private final PveStateMachine<Phase> state = new PveStateMachine<>(Phase.RETURN_HOME);
    private final BaritoneNavigator navigator = BaritoneNavigator.INSTANCE;

    private long tick;
    private long lastActionTick;
    private long lastNavigationTick;
    private long skipAppleStorageUntil;
    private long skipBoneStorageUntil;
    private BlockPos farmCenter;
    private BlockPos appleChest;
    private BlockPos boneChest;
    private BlockPos activeWorkPos;
    private boolean navigationActive;
    private boolean miningIssued;
    private boolean homeCommandSent;
    private boolean buyCommandSent;
    private boolean openedContainer;
    private boolean storageOpenRequested;
    private int plantedThisCycle;
    private int boneMealUsedThisCycle;
    private int blocksBrokenThisCycle;
    private int completedCycles;
    private int craftStep;
    private int craftSourceSlot = -1;
    private long craftStepTick;
    private int repairToolSourceSlot = -1;
    private float savedYaw;
    private float savedPitch;
    private boolean rotationSaved;

    public AppleFarmerFeature() {
        super(
                "AppleFarmer",
                "Plants, grows and harvests apple trees with storage and repair cycles",
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
    protected void onPveEnable() {
        this.tick = 0L;
        this.lastActionTick = Long.MIN_VALUE / 2L;
        this.lastNavigationTick = Long.MIN_VALUE / 2L;
        this.skipAppleStorageUntil = 0L;
        this.skipBoneStorageUntil = 0L;
        this.farmCenter = null;
        this.appleChest = null;
        this.boneChest = null;
        this.activeWorkPos = null;
        this.navigationActive = false;
        this.miningIssued = false;
        this.homeCommandSent = false;
        this.buyCommandSent = false;
        this.openedContainer = false;
        this.storageOpenRequested = false;
        this.plantedThisCycle = 0;
        this.boneMealUsedThisCycle = 0;
        this.blocksBrokenThisCycle = 0;
        this.completedCycles = 0;
        this.craftStep = 0;
        this.craftSourceSlot = -1;
        this.repairToolSourceSlot = -1;
        this.rotationSaved = false;
        this.state.reset(0L);
        beginNavigation(NavigationOptions.walking());
    }

    @Override
    protected void onPveDisable() {
        LocalPlayer player = mc.player;
        restoreCraftingInventory(player);
        restoreRepairTool(player);
        closeFeatureContainer(player);
        restoreRotation(player);
        endNavigation();
        this.activeWorkPos = null;
        this.miningIssued = false;
    }

    @Override
    protected void onPvePreempted(win.furryhook.pve.PveAutomationCoordinator.RevocationReason reason) {
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
        switch (this.state.state()) {
            case RETURN_HOME -> tickReturnHome(player);
            case FIND_FARM -> tickFindFarm(player);
            case APPROACH_FARM -> tickApproachFarm(player);
            case PLANT -> tickPlant(player, level);
            case GROW -> tickGrow(player, level);
            case BREAK_LEAVES -> tickBreakBlocks(player, level, true);
            case BREAK_LOGS -> tickBreakBlocks(player, level, false);
            case DROP_JUNK -> tickDropJunk(player);
            case DEPOSIT_APPLES_FIND -> tickFindStorage(player, level, StorageKind.APPLES);
            case DEPOSIT_APPLES_MOVE -> tickMoveToStorage(player, this.appleChest, Phase.DEPOSIT_APPLES_OPEN);
            case DEPOSIT_APPLES_OPEN -> tickDepositApples(player);
            case BONES_FIND -> tickFindStorage(player, level, StorageKind.BONES);
            case BONES_MOVE -> tickMoveToStorage(player, this.boneChest, Phase.BONES_OPEN);
            case BONES_OPEN -> tickTakeBones(player);
            case CRAFT_BONE_MEAL -> tickCraftBoneMeal(player);
            case BUY_BOTTLES -> tickBuyBottles(player);
            case REPAIR -> tickRepair(player);
        }
    }

    public Phase getPhase() {
        return this.state.state();
    }

    public int getCompletedCycles() {
        return this.completedCycles;
    }

    private void tickReturnHome(LocalPlayer player) {
        if (!this.useHomeCommand.getValue()) {
            transition(Phase.FIND_FARM);
            return;
        }

        if (!this.homeCommandSent) {
            ServerAdapter adapter = ServerAdapters.current();
            Optional<String> command = adapter.homeCommand(
                    PveManagerFeature.INSTANCE.resolvedHomeName()
            );
            if (command.isEmpty()) {
                transition(Phase.FIND_FARM);
                return;
            }
            adapter.sendCommand(player, command.get());
            this.homeCommandSent = true;
            this.lastActionTick = this.tick;
        }

        if (this.state.ticksInState(this.tick) >= HOME_SETTLE_TICKS) {
            if (PveCoordinateParser.parse(this.farmPosition.getValue()).isEmpty()) {
                this.farmCenter = player.blockPosition().immutable();
            }
            transition(Phase.FIND_FARM);
        }
    }

    private void tickFindFarm(LocalPlayer player) {
        this.farmCenter = PveCoordinateParser.parse(this.farmPosition.getValue())
                .orElseGet(() -> this.farmCenter == null
                        ? player.blockPosition().immutable()
                        : this.farmCenter);
        FarmerSignals signals = signals(
                at(player, this.farmCenter, 3.0D),
                false, false, false, false, false, false, false,
                false, false, false, false, false, false
        );
        transition(nextPhase(Phase.FIND_FARM, signals));
    }

    private void tickApproachFarm(LocalPlayer player) {
        if (this.farmCenter == null) {
            transition(Phase.FIND_FARM);
            return;
        }

        boolean atFarm = at(player, this.farmCenter, 3.0D);
        boolean timedOut = this.state.ticksInState(this.tick) > 1_200L;
        if (!atFarm && !timedOut) {
            navigateTo(this.farmCenter, 2, NavigationOptions.walking());
        }
        transition(nextPhase(
                Phase.APPROACH_FARM,
                signals(atFarm, false, false, false, false, false, false, false,
                        false, false, false, false, false, timedOut)
        ));
    }

    private void tickPlant(LocalPlayer player, ClientLevel level) {
        if (this.farmCenter == null) {
            transition(Phase.FIND_FARM);
            return;
        }

        int limit = intValue(this.treesPerCycle);
        if (this.plantedThisCycle >= limit) {
            transition(Phase.GROW);
            return;
        }

        BlockPos target = findPlantingTarget(level);
        boolean hasSapling = countItem(player, Items.OAK_SAPLING) > 0;
        boolean timedOut = this.state.ticksInState(this.tick) > 600L;
        Phase next = nextPhase(
                Phase.PLANT,
                signals(true, target != null, hasSapling, false, false, false, false, false,
                        false, false, false, false, false, timedOut)
        );
        if (next != Phase.PLANT) {
            transition(next);
            return;
        }

        if (!at(player, target, 4.0D)) {
            navigateTo(target, 2, NavigationOptions.walking());
            return;
        }
        if (!actionReady() || !selectHotbarItem(player, Items.OAK_SAPLING)) {
            return;
        }

        cancelNavigation();
        lookAt(player, Vec3.atCenterOf(target.above()));
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(target).add(0.0D, 0.5D, 0.0D),
                Direction.UP,
                target,
                false
        );
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        this.lastActionTick = this.tick;
        this.plantedThisCycle++;
    }

    private void tickGrow(LocalPlayer player, ClientLevel level) {
        if (this.farmCenter == null) {
            transition(Phase.FIND_FARM);
            return;
        }

        BlockPos sapling = findNearestBlock(level, player, true, Blocks.OAK_SAPLING);
        boolean hasBoneMeal = countItem(player, Items.BONE_MEAL) > 0;
        boolean underLimit = this.boneMealUsedThisCycle < intValue(this.boneMealPerCycle);
        boolean timedOut = this.state.ticksInState(this.tick) > 900L;
        Phase next = nextPhase(
                Phase.GROW,
                signals(true, sapling != null && underLimit, hasBoneMeal, false, false,
                        false, false, false, false, false, false, false, false, timedOut)
        );
        if (next != Phase.GROW) {
            transition(next);
            return;
        }

        if (!at(player, sapling, 4.0D)) {
            navigateTo(sapling, 2, NavigationOptions.walking());
            return;
        }
        if (!actionReady() || !selectHotbarItem(player, Items.BONE_MEAL)) {
            return;
        }

        cancelNavigation();
        lookAt(player, Vec3.atCenterOf(sapling));
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(sapling),
                Direction.UP,
                sapling,
                false
        );
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        this.lastActionTick = this.tick;
        this.boneMealUsedThisCycle++;
    }

    private void tickBreakBlocks(LocalPlayer player, ClientLevel level, boolean leaves) {
        Block[] targets = leaves
                ? new Block[]{Blocks.OAK_LEAVES}
                : new Block[]{Blocks.OAK_LOG, Blocks.OAK_WOOD};
        if (this.blocksBrokenThisCycle >= intValue(this.maxBlocksPerCycle)) {
            transition(leaves ? Phase.BREAK_LOGS : Phase.DROP_JUNK);
            return;
        }

        if (this.activeWorkPos != null && !matchesAny(level.getBlockState(this.activeWorkPos), targets)) {
            this.blocksBrokenThisCycle++;
            this.activeWorkPos = null;
            this.miningIssued = false;
            this.lastActionTick = this.tick;
        }

        if (this.activeWorkPos == null) {
            this.activeWorkPos = findNearestBlock(level, player, false, targets);
        }
        boolean timedOut = this.state.ticksInState(this.tick) > 1_800L;
        Phase current = leaves ? Phase.BREAK_LEAVES : Phase.BREAK_LOGS;
        Phase next = nextPhase(
                current,
                signals(true, this.activeWorkPos != null, true, false, false,
                        false, false, false, false, false, false, false, false, timedOut)
        );
        if (next != current) {
            transition(next);
            return;
        }

        if (!this.miningIssued || !this.navigator.isPathing() && this.tick - this.lastNavigationTick >= 40L) {
            if (!beginNavigation(NavigationOptions.mining())) {
                transition(leaves ? Phase.BREAK_LOGS : Phase.DROP_JUNK);
                return;
            }
            try {
                this.navigator.mine(0, level.getBlockState(this.activeWorkPos).getBlock());
                this.miningIssued = true;
                this.lastNavigationTick = this.tick;
            } catch (RuntimeException | LinkageError ignored) {
                this.miningIssued = false;
            }
        }
    }

    private void tickDropJunk(LocalPlayer player) {
        int junkSlot = this.dropJunk.getValue() ? findJunkSlot(player) : -1;
        boolean timedOut = this.state.ticksInState(this.tick) > 400L;
        if (junkSlot >= 0 && !timedOut) {
            if (actionReady()) {
                ItemStack stack = player.inventoryMenu.getSlot(junkSlot).getItem();
                boolean oneSapling = stack.is(Items.OAK_SAPLING);
                clickPlayerMenu(player, junkSlot, oneSapling ? 0 : 1, ContainerInput.THROW);
                this.lastActionTick = this.tick;
            }
            return;
        }

        transition(nextPhase(
                Phase.DROP_JUNK,
                maintenanceSignals(player, false, false, timedOut)
        ));
    }

    private void tickFindStorage(LocalPlayer player, ClientLevel level, StorageKind kind) {
        BlockPos found = resolveStorage(player, level, kind);
        if (kind == StorageKind.APPLES) {
            this.appleChest = found;
        } else {
            this.boneChest = found;
        }

        boolean timedOut = this.state.ticksInState(this.tick) > 200L;
        Phase current = kind == StorageKind.APPLES
                ? Phase.DEPOSIT_APPLES_FIND
                : Phase.BONES_FIND;
        if (found != null) {
            transition(kind == StorageKind.APPLES
                    ? Phase.DEPOSIT_APPLES_MOVE
                    : Phase.BONES_MOVE);
            return;
        }
        if (!timedOut) {
            return;
        }

        if (kind == StorageKind.APPLES) {
            this.skipAppleStorageUntil = this.tick + STORAGE_RETRY_TICKS;
            transition(nextPhase(current, maintenanceSignals(player, true, false, true)));
        } else {
            this.skipBoneStorageUntil = this.tick + STORAGE_RETRY_TICKS;
            transition(nextPhase(current, maintenanceSignals(player, false, true, true)));
        }
    }

    private void tickMoveToStorage(LocalPlayer player, BlockPos target, Phase openPhase) {
        Phase current = openPhase == Phase.DEPOSIT_APPLES_OPEN
                ? Phase.DEPOSIT_APPLES_MOVE
                : Phase.BONES_MOVE;
        if (target == null) {
            transition(openPhase == Phase.DEPOSIT_APPLES_OPEN
                    ? Phase.DEPOSIT_APPLES_FIND
                    : Phase.BONES_FIND);
            return;
        }

        boolean atDestination = at(player, target, 4.0D);
        boolean timedOut = this.state.ticksInState(this.tick) > 800L;
        if (!atDestination && !timedOut) {
            navigateTo(target, 2, NavigationOptions.walking());
            return;
        }
        if (timedOut) {
            if (current == Phase.DEPOSIT_APPLES_MOVE) {
                this.skipAppleStorageUntil = this.tick + STORAGE_RETRY_TICKS;
                transition(nextPhase(current, maintenanceSignals(player, true, false, true)));
            } else {
                this.skipBoneStorageUntil = this.tick + STORAGE_RETRY_TICKS;
                transition(nextPhase(current, maintenanceSignals(player, false, true, true)));
            }
            return;
        }
        transition(openPhase);
    }

    private void tickDepositApples(LocalPlayer player) {
        AbstractContainerMenu menu = openStorageMenu(player, this.appleChest);
        if (menu == null) {
            if (this.state.ticksInState(this.tick) > 200L) {
                this.skipAppleStorageUntil = this.tick + STORAGE_RETRY_TICKS;
                transition(nextPhase(
                        Phase.DEPOSIT_APPLES_OPEN,
                        maintenanceSignals(player, true, false, true)
                ));
            }
            return;
        }
        if (this.state.ticksInState(this.tick) > 400L) {
            this.skipAppleStorageUntil = this.tick + STORAGE_RETRY_TICKS;
            closeFeatureContainer(player);
            transition(nextPhase(
                    Phase.DEPOSIT_APPLES_OPEN,
                    maintenanceSignals(player, true, false, true)
            ));
            return;
        }

        int slot = findPlayerContainerSlot(menu, stack -> stack.is(Items.APPLE));
        if (slot >= 0 && actionReady()) {
            InventoryUtil.quickMoveSlot(slot);
            this.lastActionTick = this.tick;
            return;
        }
        if (slot >= 0) {
            return;
        }

        closeFeatureContainer(player);
        transition(nextPhase(
                Phase.DEPOSIT_APPLES_OPEN,
                maintenanceSignals(player, true, false, false)
        ));
    }

    private void tickTakeBones(LocalPlayer player) {
        AbstractContainerMenu menu = openStorageMenu(player, this.boneChest);
        if (menu == null) {
            if (this.state.ticksInState(this.tick) > 200L) {
                this.skipBoneStorageUntil = this.tick + STORAGE_RETRY_TICKS;
                transition(Phase.CRAFT_BONE_MEAL);
            }
            return;
        }
        if (this.state.ticksInState(this.tick) > 400L) {
            this.skipBoneStorageUntil = this.tick + STORAGE_RETRY_TICKS;
            closeFeatureContainer(player);
            transition(Phase.CRAFT_BONE_MEAL);
            return;
        }

        int targetBones = Math.max(1, intValue(this.boneMealReserve) / 3);
        int boneCount = countItem(player, Items.BONE);
        int slot = ContainerLootService.findFirst(menu, stack -> stack.is(Items.BONE));
        if (slot >= 0 && boneCount < targetBones && actionReady()) {
            ContainerLootService.quickMoveFirst(menu, stack -> stack.is(Items.BONE));
            this.lastActionTick = this.tick;
            return;
        }

        if (slot < 0 && boneCount < targetBones) {
            this.skipBoneStorageUntil = this.tick + STORAGE_RETRY_TICKS;
        }
        closeFeatureContainer(player);
        transition(Phase.CRAFT_BONE_MEAL);
    }

    private void tickCraftBoneMeal(LocalPlayer player) {
        if (this.state.ticksInState(this.tick) > 400L) {
            restoreCraftingInventory(player);
            this.skipBoneStorageUntil = this.tick + STORAGE_RETRY_TICKS;
            transition(Phase.PLANT);
            return;
        }
        int reserve = intValue(this.boneMealReserve);
        if (countItem(player, Items.BONE_MEAL) >= reserve || countItem(player, Items.BONE) <= 0) {
            restoreCraftingInventory(player);
            transition(nextPhase(
                    Phase.CRAFT_BONE_MEAL,
                    maintenanceSignals(player, false, false, false)
            ));
            return;
        }
        if (player.containerMenu != player.inventoryMenu || this.openedContainer) {
            closeFeatureContainer(player);
            return;
        }

        switch (this.craftStep) {
            case 0 -> {
                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    restoreCraftingInventory(player);
                    return;
                }
                this.craftSourceSlot = findPlayerSlot(player, Items.BONE);
                if (this.craftSourceSlot < 0) {
                    transition(nextPhase(
                            Phase.CRAFT_BONE_MEAL,
                            maintenanceSignals(player, false, true, false)
                    ));
                    return;
                }
                clickPlayerMenu(player, this.craftSourceSlot, 0, ContainerInput.PICKUP);
                clickPlayerMenu(player, CRAFT_INPUT_SLOT, 0, ContainerInput.PICKUP);
                this.craftStep = 1;
                this.craftStepTick = this.tick;
            }
            case 1 -> {
                ItemStack result = player.inventoryMenu.getSlot(CRAFT_RESULT_SLOT).getItem();
                if (result.is(Items.BONE_MEAL)) {
                    clickPlayerMenu(player, CRAFT_RESULT_SLOT, 0, ContainerInput.QUICK_MOVE);
                    this.craftStep = 2;
                    this.craftStepTick = this.tick;
                } else if (this.tick - this.craftStepTick > 20L) {
                    this.craftStep = 2;
                    this.craftStepTick = this.tick;
                }
            }
            case 2 -> {
                if (this.tick - this.craftStepTick < 2L) {
                    return;
                }
                restoreCraftingInventory(player);
                this.craftStep = 0;
                this.craftSourceSlot = -1;
            }
            default -> {
                restoreCraftingInventory(player);
                this.craftStep = 0;
            }
        }
    }

    private void tickBuyBottles(LocalPlayer player) {
        if (countItem(player, Items.EXPERIENCE_BOTTLE) > 0) {
            transition(Phase.REPAIR);
            return;
        }

        String command = this.xpBottleBuyCommand.getValue().trim();
        if (!this.buyXpBottles.getValue() || command.isEmpty()) {
            transition(Phase.PLANT);
            return;
        }
        if (!this.buyCommandSent) {
            ServerAdapters.current().sendCommand(player, command);
            this.buyCommandSent = true;
            this.lastActionTick = this.tick;
        }
        if (player.containerMenu != player.inventoryMenu) {
            this.openedContainer = true;
        }
        if (this.state.ticksInState(this.tick) > 160L) {
            closeFeatureContainer(player);
            transition(countItem(player, Items.EXPERIENCE_BOTTLE) > 0
                    ? Phase.REPAIR
                    : Phase.PLANT);
        }
    }

    private void tickRepair(LocalPlayer player) {
        if (this.repairToolSourceSlot < 0) {
            int toolSlot = findRepairToolSlot(player, this.repairBelow.getValue());
            if (toolSlot < 0) {
                transition(Phase.PLANT);
                return;
            }
            saveRotation(player);
            swapWithOffhand(player, toolSlot);
            this.repairToolSourceSlot = toolSlot;
            this.lastActionTick = this.tick;
            return;
        }

        ItemStack tool = player.getOffhandItem();
        boolean repaired = !needsRepair(tool, this.repairTo.getValue());
        int bottleSlot = findPlayerSlot(player, Items.EXPERIENCE_BOTTLE);
        boolean timedOut = this.state.ticksInState(this.tick) > 800L;
        if (repaired || bottleSlot < 0 || timedOut) {
            restoreRepairTool(player);
            transition(Phase.PLANT);
            return;
        }
        if (!actionReady()) {
            return;
        }

        lookAt(player, player.position().add(0.0D, -1.0D, 0.0D));
        useFromPlayerSlot(player, bottleSlot);
        this.lastActionTick = this.tick;
    }

    private AbstractContainerMenu openStorageMenu(LocalPlayer player, BlockPos storage) {
        if ((this.storageOpenRequested || this.openedContainer)
                && InventoryUtil.isContainerScreenOpen()) {
            AbstractContainerMenu menu = InventoryUtil.getOpenMenu();
            if (menu != null && menu != player.inventoryMenu) {
                this.openedContainer = true;
                return menu;
            }
        }
        if (storage == null || !at(player, storage, 4.0D) || !actionReady()) {
            return null;
        }

        cancelNavigation();
        lookAt(player, Vec3.atCenterOf(storage));
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(storage),
                Direction.UP,
                storage,
                false
        );
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        this.storageOpenRequested = true;
        this.lastActionTick = this.tick;
        return null;
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

    private BlockPos resolveStorage(LocalPlayer player, ClientLevel level, StorageKind kind) {
        String setting = kind == StorageKind.APPLES
                ? this.appleStorage.getValue()
                : this.boneStorage.getValue();
        Optional<BlockPos> configured = PveCoordinateParser.parse(setting);
        if (configured.isPresent()) {
            return configured.get();
        }

        BlockPos origin = this.farmCenter == null ? player.blockPosition() : this.farmCenter;
        BlockPos signed = findSignedChest(level, origin, kind);
        return signed != null ? signed : findNearestChest(level, player, origin);
    }

    private BlockPos findSignedChest(ClientLevel level, BlockPos origin, StorageKind kind) {
        int radius = Math.min(16, intValue(this.farmRadius) + 4);
        int yRadius = Math.min(8, intValue(this.verticalScan));
        for (BlockPos cursor : BlockPos.betweenClosed(
                origin.offset(-radius, -yRadius, -radius),
                origin.offset(radius, yRadius, radius))) {
            if (!(level.getBlockEntity(cursor) instanceof SignBlockEntity sign)) {
                continue;
            }
            List<String> lines = new ArrayList<>(4);
            for (int line = 0; line < 4; line++) {
                lines.add(sign.getFrontText().getMessage(line, false).getString());
                lines.add(sign.getBackText().getMessage(line, false).getString());
            }
            if (!matchesStorageLabel(lines, kind)) {
                continue;
            }
            BlockPos chest = findChestNear(level, cursor, 2);
            if (chest != null) {
                return chest;
            }
        }
        return null;
    }

    private BlockPos findNearestChest(ClientLevel level, LocalPlayer player, BlockPos origin) {
        int radius = Math.min(16, intValue(this.farmRadius) + 4);
        int yRadius = Math.min(8, intValue(this.verticalScan));
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos cursor : BlockPos.betweenClosed(
                origin.offset(-radius, -yRadius, -radius),
                origin.offset(radius, yRadius, radius))) {
            if (!isNormalChest(level.getBlockState(cursor))) {
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

    private static BlockPos findChestNear(ClientLevel level, BlockPos sign, int radius) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos cursor : BlockPos.betweenClosed(
                sign.offset(-radius, -radius, -radius),
                sign.offset(radius, radius, radius))) {
            if (!isNormalChest(level.getBlockState(cursor))) {
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

    static boolean matchesStorageLabel(Iterable<String> lines, StorageKind kind) {
        for (String line : lines) {
            String normalized = normalizeLabel(line);
            if (kind == StorageKind.APPLES
                    && (normalized.contains("apple") || normalized.contains("яблок"))) {
                return true;
            }
            if (kind == StorageKind.BONES
                    && (normalized.contains("bone") || normalized.contains("кост"))) {
                return true;
            }
        }
        return false;
    }

    static String normalizeLabel(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("§.", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private BlockPos findPlantingTarget(ClientLevel level) {
        int radius = intValue(this.farmRadius);
        int yRadius = Math.min(3, intValue(this.verticalScan));
        for (BlockPos base : BlockPos.betweenClosed(
                this.farmCenter.offset(-radius, -yRadius, -radius),
                this.farmCenter.offset(radius, yRadius, radius))) {
            BlockState state = level.getBlockState(base);
            if ((!state.is(Blocks.DIRT)
                    && !state.is(Blocks.GRASS_BLOCK)
                    && !state.is(Blocks.COARSE_DIRT)
                    && !state.is(Blocks.PODZOL))
                    || !level.isEmptyBlock(base.above())
                    || treeNearby(level, base.above(), 2)) {
                continue;
            }
            return base.immutable();
        }
        return null;
    }

    private static boolean treeNearby(ClientLevel level, BlockPos center, int radius) {
        for (BlockPos cursor : BlockPos.betweenClosed(
                center.offset(-radius, -1, -radius),
                center.offset(radius, 3, radius))) {
            BlockState state = level.getBlockState(cursor);
            if (state.is(Blocks.OAK_SAPLING)
                    || state.is(Blocks.OAK_LOG)
                    || state.is(Blocks.OAK_WOOD)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findNearestBlock(ClientLevel level,
                                      LocalPlayer player,
                                      boolean saplingOnly,
                                      Block... blocks) {
        if (this.farmCenter == null) {
            return null;
        }
        int radius = intValue(this.farmRadius);
        int yRadius = intValue(this.verticalScan);
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos cursor : BlockPos.betweenClosed(
                this.farmCenter.offset(-radius, -yRadius, -radius),
                this.farmCenter.offset(radius, yRadius, radius))) {
            BlockState state = level.getBlockState(cursor);
            if (!matchesAny(state, blocks)) {
                continue;
            }
            if (saplingOnly && !state.is(Blocks.OAK_SAPLING)) {
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

    private int findJunkSlot(LocalPlayer player) {
        int totalSaplings = countItem(player, Items.OAK_SAPLING);
        int reserve = intValue(this.saplingReserve);
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.OAK_SAPLING) && totalSaplings > reserve) {
                return slot;
            }
            if (stack.is(Items.STICK)
                    || stack.is(Items.OAK_LEAVES)
                    || stack.is(Items.WHEAT_SEEDS)
                    || stack.is(Items.BEETROOT_SEEDS)
                    || stack.is(Items.MELON_SEEDS)
                    || stack.is(Items.PUMPKIN_SEEDS)) {
                return slot;
            }
        }
        return -1;
    }

    private FarmerSignals maintenanceSignals(LocalPlayer player,
                                             boolean ignoreAppleStorage,
                                             boolean ignoreBoneStorage,
                                             boolean timedOut) {
        int apples = countItem(player, Items.APPLE);
        boolean deposit = !ignoreAppleStorage
                && this.tick >= this.skipAppleStorageUntil
                && apples > 0
                && (apples >= intValue(this.depositAppleStacks) * 64 || freeSlots(player) <= 2);
        boolean fetchBones = !ignoreBoneStorage
                && this.takeBones.getValue()
                && this.tick >= this.skipBoneStorageUntil
                && countItem(player, Items.BONE_MEAL) < intValue(this.boneMealReserve);
        ItemStack repairTool = findRepairTool(player, this.repairBelow.getValue());
        return signals(
                true,
                false,
                false,
                this.dropJunk.getValue() && findJunkSlot(player) >= 0,
                deposit,
                false,
                false,
                false,
                fetchBones,
                countItem(player, Items.BONE) > 0,
                !repairTool.isEmpty(),
                countItem(player, Items.EXPERIENCE_BOTTLE) > 0,
                this.buyXpBottles.getValue() && !this.xpBottleBuyCommand.getValue().isBlank(),
                timedOut
        );
    }

    private static FarmerSignals signals(boolean atFarm,
                                         boolean workRemaining,
                                         boolean hasResource,
                                         boolean hasJunk,
                                         boolean shouldDepositApples,
                                         boolean destinationKnown,
                                         boolean atDestination,
                                         boolean inventoryWorkRemaining,
                                         boolean shouldFetchBones,
                                         boolean hasBones,
                                         boolean needsRepair,
                                         boolean hasBottles,
                                         boolean canBuyBottles,
                                         boolean timedOut) {
        return new FarmerSignals(
                atFarm,
                workRemaining,
                hasResource,
                hasJunk,
                shouldDepositApples,
                destinationKnown,
                atDestination,
                inventoryWorkRemaining,
                shouldFetchBones,
                hasBones,
                needsRepair,
                hasBottles,
                canBuyBottles,
                timedOut
        );
    }

    static Phase nextPhase(Phase phase, FarmerSignals signals) {
        return switch (phase) {
            case RETURN_HOME -> signals.atFarm() || signals.timedOut()
                    ? Phase.FIND_FARM
                    : Phase.RETURN_HOME;
            case FIND_FARM -> signals.atFarm() ? Phase.PLANT : Phase.APPROACH_FARM;
            case APPROACH_FARM -> signals.atFarm()
                    ? Phase.PLANT
                    : signals.timedOut() ? Phase.RETURN_HOME : Phase.APPROACH_FARM;
            case PLANT -> signals.workRemaining() && signals.hasResource() && !signals.timedOut()
                    ? Phase.PLANT
                    : Phase.GROW;
            case GROW -> signals.workRemaining() && signals.hasResource() && !signals.timedOut()
                    ? Phase.GROW
                    : Phase.BREAK_LEAVES;
            case BREAK_LEAVES -> signals.workRemaining() && !signals.timedOut()
                    ? Phase.BREAK_LEAVES
                    : Phase.BREAK_LOGS;
            case BREAK_LOGS -> signals.workRemaining() && !signals.timedOut()
                    ? Phase.BREAK_LOGS
                    : Phase.DROP_JUNK;
            case DROP_JUNK -> signals.hasJunk() && !signals.timedOut()
                    ? Phase.DROP_JUNK
                    : maintenancePhase(signals);
            case DEPOSIT_APPLES_FIND -> signals.destinationKnown()
                    ? Phase.DEPOSIT_APPLES_MOVE
                    : signals.timedOut() ? maintenanceAfterApples(signals) : Phase.DEPOSIT_APPLES_FIND;
            case DEPOSIT_APPLES_MOVE -> signals.atDestination()
                    ? Phase.DEPOSIT_APPLES_OPEN
                    : signals.timedOut() ? maintenanceAfterApples(signals) : Phase.DEPOSIT_APPLES_MOVE;
            case DEPOSIT_APPLES_OPEN -> signals.inventoryWorkRemaining() && !signals.timedOut()
                    ? Phase.DEPOSIT_APPLES_OPEN
                    : maintenanceAfterApples(signals);
            case BONES_FIND -> signals.destinationKnown()
                    ? Phase.BONES_MOVE
                    : signals.timedOut() ? maintenanceAfterBones(signals) : Phase.BONES_FIND;
            case BONES_MOVE -> signals.atDestination()
                    ? Phase.BONES_OPEN
                    : signals.timedOut() ? maintenanceAfterBones(signals) : Phase.BONES_MOVE;
            case BONES_OPEN -> signals.inventoryWorkRemaining() && !signals.timedOut()
                    ? Phase.BONES_OPEN
                    : Phase.CRAFT_BONE_MEAL;
            case CRAFT_BONE_MEAL -> signals.hasBones() && signals.workRemaining() && !signals.timedOut()
                    ? Phase.CRAFT_BONE_MEAL
                    : repairPhase(signals);
            case BUY_BOTTLES -> signals.hasBottles()
                    ? Phase.REPAIR
                    : signals.timedOut() || !signals.canBuyBottles()
                    ? Phase.PLANT
                    : Phase.BUY_BOTTLES;
            case REPAIR -> signals.needsRepair() && signals.hasBottles() && !signals.timedOut()
                    ? Phase.REPAIR
                    : Phase.PLANT;
        };
    }

    private static Phase maintenancePhase(FarmerSignals signals) {
        if (signals.shouldDepositApples()) {
            return Phase.DEPOSIT_APPLES_FIND;
        }
        if (signals.hasBones()) {
            return Phase.CRAFT_BONE_MEAL;
        }
        if (signals.shouldFetchBones()) {
            return Phase.BONES_FIND;
        }
        return repairPhase(signals);
    }

    private static Phase maintenanceAfterApples(FarmerSignals signals) {
        if (signals.hasBones()) {
            return Phase.CRAFT_BONE_MEAL;
        }
        if (signals.shouldFetchBones()) {
            return Phase.BONES_FIND;
        }
        return repairPhase(signals);
    }

    private static Phase maintenanceAfterBones(FarmerSignals signals) {
        return signals.hasBones() ? Phase.CRAFT_BONE_MEAL : repairPhase(signals);
    }

    private static Phase repairPhase(FarmerSignals signals) {
        if (!signals.needsRepair()) {
            return Phase.PLANT;
        }
        if (signals.hasBottles()) {
            return Phase.REPAIR;
        }
        return signals.canBuyBottles() ? Phase.BUY_BOTTLES : Phase.PLANT;
    }

    private void transition(Phase next) {
        Phase previous = this.state.state();
        if (!this.state.transition(next, this.tick)) {
            return;
        }
        cancelNavigation();
        this.activeWorkPos = null;
        this.miningIssued = false;
        this.lastNavigationTick = Long.MIN_VALUE / 2L;
        this.homeCommandSent = false;
        this.buyCommandSent = false;
        if (previous == Phase.REPAIR && next != Phase.REPAIR) {
            restoreRepairTool(mc.player);
            restoreRotation(mc.player);
        }
        if (next == Phase.PLANT && previous != Phase.FIND_FARM && previous != Phase.APPROACH_FARM) {
            this.completedCycles++;
            this.plantedThisCycle = 0;
            this.boneMealUsedThisCycle = 0;
            this.blocksBrokenThisCycle = 0;
        }
        if (next != Phase.CRAFT_BONE_MEAL) {
            this.craftStep = 0;
            this.craftSourceSlot = -1;
        }
    }

    private boolean beginNavigation(NavigationOptions options) {
        if (!this.navigator.isAvailable()) {
            return false;
        }
        try {
            this.navigator.begin(
                    PveManagerFeature.INSTANCE.configureNavigation(options)
            );
            this.navigationActive = true;
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            this.navigationActive = false;
            return false;
        }
    }

    private void navigateTo(BlockPos target, int radius, NavigationOptions options) {
        if (target == null || !beginNavigation(options)) {
            return;
        }
        Optional<BlockPos> currentGoal = this.navigator.currentGoal();
        boolean sameGoal = currentGoal.isPresent() && currentGoal.get().equals(target);
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

    private boolean actionReady() {
        return this.tick - this.lastActionTick >= ACTION_INTERVAL_TICKS;
    }

    private boolean selectHotbarItem(LocalPlayer player, net.minecraft.world.item.Item item) {
        int slot = findPlayerSlot(player, item);
        if (slot < 0) {
            return false;
        }
        int selected = player.getInventory().getSelectedSlot();
        if (slot >= InventoryMenu.USE_ROW_SLOT_START && slot < InventoryMenu.USE_ROW_SLOT_END) {
            player.getInventory().setSelectedSlot(slot - InventoryMenu.USE_ROW_SLOT_START);
            return true;
        }
        clickPlayerMenu(player, slot, selected, ContainerInput.SWAP);
        return player.getMainHandItem().is(item);
    }

    private void useFromPlayerSlot(LocalPlayer player, int slot) {
        int selected = player.getInventory().getSelectedSlot();
        if (slot >= InventoryMenu.USE_ROW_SLOT_START && slot < InventoryMenu.USE_ROW_SLOT_END) {
            int hotbar = slot - InventoryMenu.USE_ROW_SLOT_START;
            int old = selected;
            player.getInventory().setSelectedSlot(hotbar);
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            player.getInventory().setSelectedSlot(old);
            return;
        }

        clickPlayerMenu(player, slot, selected, ContainerInput.SWAP);
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        clickPlayerMenu(player, slot, selected, ContainerInput.SWAP);
    }

    private void swapWithOffhand(LocalPlayer player, int slot) {
        clickPlayerMenu(player, slot, OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
    }

    private void restoreRepairTool(LocalPlayer player) {
        if (this.repairToolSourceSlot < 0) {
            return;
        }
        if (player != null
                && player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.isValidSlotIndex(this.repairToolSourceSlot)) {
            swapWithOffhand(player, this.repairToolSourceSlot);
        }
        this.repairToolSourceSlot = -1;
    }

    private void restoreCraftingInventory(LocalPlayer player) {
        if (player == null || mc.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        if (!player.inventoryMenu.getCarried().isEmpty()) {
            int destination = validEmptySlot(player, this.craftSourceSlot)
                    ? this.craftSourceSlot
                    : findEmptyPlayerSlot(player);
            if (destination >= 0) {
                clickPlayerMenu(player, destination, 0, ContainerInput.PICKUP);
            }
        }
        ItemStack input = player.inventoryMenu.getSlot(CRAFT_INPUT_SLOT).getItem();
        if (!input.isEmpty() && player.inventoryMenu.getCarried().isEmpty()) {
            int destination = validEmptySlot(player, this.craftSourceSlot)
                    ? this.craftSourceSlot
                    : findEmptyPlayerSlot(player);
            if (destination >= 0) {
                clickPlayerMenu(player, CRAFT_INPUT_SLOT, 0, ContainerInput.PICKUP);
                clickPlayerMenu(player, destination, 0, ContainerInput.PICKUP);
            }
        }
    }

    private static boolean validEmptySlot(LocalPlayer player, int slot) {
        return slot >= 0
                && player.inventoryMenu.isValidSlotIndex(slot)
                && player.inventoryMenu.getSlot(slot).getItem().isEmpty();
    }

    private static int findEmptyPlayerSlot(LocalPlayer player) {
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            if (player.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private void clickPlayerMenu(LocalPlayer player, int slot, int button, ContainerInput input) {
        if (mc.gameMode == null || !player.inventoryMenu.isValidSlotIndex(slot)) {
            return;
        }
        mc.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                slot,
                button,
                input,
                player
        );
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

    private static int findPlayerSlot(LocalPlayer player, net.minecraft.world.item.Item item) {
        for (int slot = InventoryMenu.USE_ROW_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            if (player.inventoryMenu.getSlot(slot).getItem().is(item)) {
                return slot;
            }
        }
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_START;
             slot++) {
            if (player.inventoryMenu.getSlot(slot).getItem().is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int countItem(LocalPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        if (player.getOffhandItem().is(item)) {
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

    private static ItemStack findRepairTool(LocalPlayer player, double thresholdPercent) {
        int slot = findRepairToolSlot(player, thresholdPercent);
        return slot < 0 ? ItemStack.EMPTY : player.inventoryMenu.getSlot(slot).getItem();
    }

    private static int findRepairToolSlot(LocalPlayer player, double thresholdPercent) {
        int bestSlot = -1;
        double worstRemaining = Double.POSITIVE_INFINITY;
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (!needsRepair(stack, thresholdPercent) || enchantmentLevel(stack, "mending") <= 0) {
                continue;
            }
            double remaining = remainingDurability(stack);
            if (remaining < worstRemaining) {
                worstRemaining = remaining;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static boolean needsRepair(ItemStack stack, double thresholdPercent) {
        return !stack.isEmpty()
                && stack.isDamageableItem()
                && enchantmentLevel(stack, "mending") > 0
                && remainingDurability(stack) < thresholdPercent;
    }

    private static double remainingDurability(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return 100.0D;
        }
        return (stack.getMaxDamage() - stack.getDamageValue()) * 100.0D
                / Math.max(1, stack.getMaxDamage());
    }

    private static int enchantmentLevel(ItemStack stack, String path) {
        if (stack.isEmpty()) {
            return 0;
        }
        for (var entry : stack.getEnchantments().entrySet()) {
            var key = entry.getKey().unwrapKey();
            if (key.isPresent() && key.get().identifier().getPath().equals(path)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private void lookAt(LocalPlayer player, Vec3 target) {
        if (!PveManagerFeature.INSTANCE.rotate.getValue()) {
            return;
        }
        saveRotation(player);
        Vec3 delta = target.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        player.setYRot(yaw);
        player.setXRot(pitch);
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

    private static boolean at(LocalPlayer player, BlockPos position, double radius) {
        return position != null
                && player.position().distanceToSqr(Vec3.atCenterOf(position)) <= radius * radius;
    }

    private static boolean matchesAny(BlockState state, Block... blocks) {
        for (Block block : blocks) {
            if (state.is(block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNormalChest(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private static int intValue(NumberSetting setting) {
        return (int) Math.round(setting.getValue());
    }
}
