package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.PveStateMachine;
import win.furryhook.pve.economy.AuctionPriceScanner;
import win.furryhook.pve.economy.CommandCooldown;
import win.furryhook.pve.economy.CraftingMenuController;
import win.furryhook.pve.economy.EconomyAutomationPolicy;
import win.furryhook.pve.economy.EconomyChat;
import win.furryhook.pve.economy.EconomyCommands;
import win.furryhook.pve.economy.EconomyInventory;
import win.furryhook.pve.economy.EconomyItemText;
import win.furryhook.pve.economy.EconomyMenus;
import win.furryhook.pve.economy.EconomyNavigator;
import win.furryhook.pve.economy.EconomyTextParser;
import win.furryhook.pve.economy.NearbyEconomyBlocks;
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.pve.server.ServerAdapter;
import win.furryhook.pve.server.ServerAdapters;
import win.furryhook.pve.server.ServerProfile;
import win.furryhook.utils.inventory.ContainerLootService;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.OptionalLong;

public final class AutoCrafterFeature extends PveFeature {
    public static final String RECIPE_ENCHANTED_GOLDEN_APPLE = "Enchanted Golden Apple";

    private static final int SEARCH_RADIUS = 16;
    private static final long MOVE_TIMEOUT_TICKS = 240L;
    private static final long OPEN_TIMEOUT_TICKS = 100L;
    private static final long AUCTION_RETRY_TICKS = 1_200L;

    public final ModeSetting craft = register(new ModeSetting(
            "Craft",
            RECIPE_ENCHANTED_GOLDEN_APPLE,
            RECIPE_ENCHANTED_GOLDEN_APPLE
    ));
    public final BooleanSetting autoSell = register(new BooleanSetting("Auto Sell", true));
    public final BooleanSetting takeCrafterChest = register(
            new BooleanSetting("Take from Crafter Chest", false)
                    .visibleWhen(() -> !SynchronizationFeature.isActive())
    );

    private final PveStateMachine<State> machine = new PveStateMachine<>(State.WAIT);
    private final EconomyNavigator navigator =
            new EconomyNavigator(BaritoneNavigator.INSTANCE);
    private final CraftingMenuController crafting = new CraftingMenuController();
    private final CommandCooldown actionCooldown = new CommandCooldown();
    private final CommandCooldown commandCooldown = new CommandCooldown();

    private long lastTick;
    private long nextWorkTick;
    private long auctionRetryTick;
    private BlockPos targetBlock;
    private ChestKind chestKind;
    private CraftingMenuController.Recipe pendingRecipe;
    private int ownedContainerId = -1;
    private int lootActions;
    private boolean resourcesClaimed;

    private int saleCount;
    private long salePrice;
    private int originalSelectedSlot = -1;
    private int saleSwapMenuSlot = -1;
    private int saleHotbarSlot = -1;
    private boolean saleConfirmed;
    private boolean saleRejected;

    public AutoCrafterFeature() {
        super(
                "AutoCrafter",
                "Crafts enchanted golden apples from signed resource chests",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.FEATURE
        );
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }
        long tick = client.level.getGameTime();
        this.lastTick = tick;
        if (ServerAdapters.current().profile() != ServerProfile.FUNTIME) {
            if (this.resourcesClaimed
                    || !this.machine.is(State.WAIT)
                    || this.ownedContainerId >= 0) {
                finishCycle(client, tick, 20L);
            }
            return;
        }

        switch (this.machine.state()) {
            case WAIT -> {
                if (tick >= this.nextWorkTick) {
                    this.machine.transition(State.SELECT, tick);
                }
            }
            case SELECT -> selectWork(client, player, tick);
            case FIND_CHEST -> findChest(client, player, tick);
            case MOVE_CHEST -> moveToTarget(player, State.OPEN_CHEST, tick);
            case OPEN_CHEST -> openChest(client, player, tick);
            case LOOT_CHEST -> lootChest(client, player, tick);
            case FIND_TABLE -> findTable(client, player, tick);
            case MOVE_TABLE -> moveToTarget(player, State.OPEN_TABLE, tick);
            case OPEN_TABLE -> openTable(client, player, tick);
            case CRAFT -> craft(client, tick);
            case OPEN_AUCTION_SEARCH -> openAuctionSearch(client, player, tick);
            case WAIT_AUCTION_SEARCH -> waitAuctionSearch(client, tick);
            case LIST_AUCTION -> listAuction(client, player, tick);
            case WAIT_SALE_CONFIRMATION -> waitSaleConfirmation(client, tick);
        }
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() != PacketReceiveEvent.Phase.PRE) {
            return;
        }
        String text = EconomyChat.incomingText(event.getPacket());
        if (text == null) {
            return;
        }
        String normalized = EconomyTextParser.normalize(text);
        Minecraft.getInstance().execute(() -> {
            if (!isEnabled()) {
                return;
            }
            if (EconomyTextParser.containsAny(
                    normalized,
                    "не удалось выставить",
                    "хранилищ",
                    "слот",
                    "ah rent",
                    "auction slots are full"
            )) {
                this.saleRejected = true;
                this.auctionRetryTick = this.lastTick + AUCTION_RETRY_TICKS;
            } else if (EconomyTextParser.containsAny(
                    normalized,
                    "выставлен на продажу",
                    "listed for sale"
            )) {
                this.saleConfirmed = true;
                this.auctionRetryTick = 0L;
            }
        });
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        resetRuntime(false);
    }

    @Override
    protected void onPveEnable() {
        resetRuntime(false);
    }

    @Override
    protected void onPveDisable() {
        resetRuntime(true);
    }

    @Override
    protected void onPvePreempted(PveAutomationCoordinator.RevocationReason reason) {
        resetRuntime(true);
    }

    private void selectWork(Minecraft client, LocalPlayer player, long tick) {
        if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu) {
            finishCycle(client, tick, 20L);
            return;
        }
        if (!ensureCycleResources(tick)) {
            return;
        }
        int output = EconomyInventory.count(player, Items.ENCHANTED_GOLDEN_APPLE);
        int apples = EconomyInventory.count(player, Items.APPLE);
        int blocks = EconomyInventory.count(player, Items.GOLD_BLOCK);
        int ingots = EconomyInventory.count(player, Items.GOLD_INGOT);
        boolean canTake = canTakeResources();

        switch (EconomyAutomationPolicy.crafterAction(
                output,
                apples,
                blocks,
                ingots,
                this.autoSell.getValue(),
                canTake,
                tick >= this.auctionRetryTick
        )) {
            case SELL -> this.machine.transition(State.OPEN_AUCTION_SEARCH, tick);
            case CRAFT_APPLE -> {
                this.pendingRecipe = CraftingMenuController.Recipe.ENCHANTED_GOLDEN_APPLE;
                this.machine.transition(State.FIND_TABLE, tick);
            }
            case CRAFT_GOLD_BLOCK -> {
                this.pendingRecipe = CraftingMenuController.Recipe.GOLD_BLOCK;
                this.machine.transition(State.FIND_TABLE, tick);
            }
            case TAKE_APPLES -> {
                this.chestKind = SynchronizationFeature.isActive()
                        ? ChestKind.APPLE
                        : ChestKind.CRAFTER;
                this.machine.transition(State.FIND_CHEST, tick);
            }
            case TAKE_GOLD -> {
                this.chestKind = SynchronizationFeature.isActive()
                        ? ChestKind.GOLD
                        : ChestKind.CRAFTER;
                this.machine.transition(State.FIND_CHEST, tick);
            }
            case WAIT -> finishCycle(client, tick, 40L);
        }
    }

    private void findChest(Minecraft client, LocalPlayer player, long tick) {
        this.targetBlock = findSignedChest(client, player, this.chestKind);
        if (this.targetBlock == null) {
            if (this.autoSell.getValue()
                    && tick >= this.auctionRetryTick
                    && EconomyInventory.count(player, Items.ENCHANTED_GOLDEN_APPLE) > 0) {
                this.machine.transition(State.OPEN_AUCTION_SEARCH, tick);
                return;
            }
            finishCycle(client, tick, 100L);
            return;
        }
        this.machine.transition(State.MOVE_CHEST, tick);
    }

    private void findTable(Minecraft client, LocalPlayer player, long tick) {
        this.targetBlock = NearbyEconomyBlocks.nearestCraftingTable(
                client.level,
                player,
                SEARCH_RADIUS
        );
        if (this.targetBlock == null) {
            finishCycle(client, tick, 100L);
            return;
        }
        this.machine.transition(State.MOVE_TABLE, tick);
    }

    private void moveToTarget(LocalPlayer player, State next, long tick) {
        if (this.targetBlock == null) {
            finishCycle(Minecraft.getInstance(), tick, 100L);
            return;
        }
        if (EconomyNavigator.arrived(player, this.targetBlock, 3.7)) {
            this.navigator.cancel();
            this.machine.transition(next, tick);
            return;
        }
        this.navigator.moveTo(player, this.targetBlock, 3);
        if (this.machine.ticksInState(tick) > MOVE_TIMEOUT_TICKS) {
            finishCycle(Minecraft.getInstance(), tick, 100L);
        }
    }

    private void openChest(Minecraft client, LocalPlayer player, long tick) {
        if (player.containerMenu instanceof ChestMenu menu
                && player.containerMenu != player.inventoryMenu) {
            this.ownedContainerId = menu.containerId;
            this.lootActions = 0;
            this.machine.transition(State.LOOT_CHEST, tick);
            return;
        }
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, 100L);
            return;
        }
        interactTarget(client, player, tick);
    }

    private void openTable(Minecraft client, LocalPlayer player, long tick) {
        if (player.containerMenu instanceof CraftingMenu menu) {
            this.ownedContainerId = menu.containerId;
            this.crafting.reset();
            this.machine.transition(State.CRAFT, tick);
            return;
        }
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, 100L);
            return;
        }
        interactTarget(client, player, tick);
    }

    private void interactTarget(Minecraft client, LocalPlayer player, long tick) {
        if (this.targetBlock == null || !this.actionCooldown.tryAcquire(tick, 10L)) {
            return;
        }
        client.gameMode.useItemOn(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        Vec3.atCenterOf(this.targetBlock),
                        Direction.UP,
                        this.targetBlock,
                        false
                )
        );
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void lootChest(Minecraft client, LocalPlayer player, long tick) {
        if (!(player.containerMenu instanceof ChestMenu menu)
                || menu.containerId != this.ownedContainerId) {
            finishCycle(client, tick, 60L);
            return;
        }
        int apples = EconomyInventory.count(player, Items.APPLE);
        int blocks = EconomyInventory.count(player, Items.GOLD_BLOCK);
        int ingots = EconomyInventory.count(player, Items.GOLD_INGOT);
        if (apples >= 1 && (blocks >= 8 || ingots >= 9)) {
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
            return;
        }
        if (!this.actionCooldown.ready(tick)) {
            return;
        }
        int source = EconomyMenus.findContainerSlot(menu, stack ->
                shouldLoot(stack, apples, blocks, ingots)
        );
        if (source < 0 || this.lootActions++ >= 20) {
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
            return;
        }
        if (EconomyMenus.quickMove(client, menu, source)) {
            this.actionCooldown.tryAcquire(tick, 3L);
        }
    }

    private boolean shouldLoot(ItemStack stack, int apples, int blocks, int ingots) {
        return switch (this.chestKind) {
            case APPLE -> apples < 1 && stack.is(Items.APPLE);
            case GOLD -> blocks < 8
                    && (stack.is(Items.GOLD_BLOCK) || ingots < 72 && stack.is(Items.GOLD_INGOT));
            case CRAFTER -> apples < 1 && stack.is(Items.APPLE)
                    || blocks < 8
                    && (stack.is(Items.GOLD_BLOCK) || ingots < 72 && stack.is(Items.GOLD_INGOT));
        };
    }

    private void craft(Minecraft client, long tick) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)
                || menu.containerId != this.ownedContainerId
                || this.pendingRecipe == null) {
            finishCycle(client, tick, 60L);
            return;
        }
        if (!this.actionCooldown.ready(tick)) {
            return;
        }
        CraftingMenuController.Result result = this.crafting.tick(
                client,
                menu,
                this.pendingRecipe
        );
        this.actionCooldown.tryAcquire(tick, 2L);
        if (result == CraftingMenuController.Result.CRAFTED) {
            closeOwned(client);
            this.pendingRecipe = null;
            this.machine.transition(State.SELECT, tick);
        } else if (result == CraftingMenuController.Result.FAILED) {
            this.crafting.cleanup(client, menu);
            finishCycle(client, tick, 100L);
        }
    }

    private void openAuctionSearch(Minecraft client, LocalPlayer player, long tick) {
        if (!this.commandCooldown.ready(tick)) {
            return;
        }
        ServerAdapter adapter = ServerAdapters.current();
        String query = "зачарованное золотое яблоко";
        adapter.auctionCommand()
                .flatMap(root -> EconomyCommands.auctionSearch(root, query))
                .ifPresentOrElse(command -> {
                    this.saleCount = largestStackCount(player, Items.ENCHANTED_GOLDEN_APPLE);
                    if (this.saleCount <= 0) {
                        finishCycle(client, tick, 40L);
                        return;
                    }
                    adapter.sendCommand(player, command);
                    this.commandCooldown.tryAcquire(tick, 40L);
                    this.machine.transition(State.WAIT_AUCTION_SEARCH, tick);
                }, () -> finishCycle(client, tick, 100L));
    }

    private void waitAuctionSearch(Minecraft client, long tick) {
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, 100L);
            return;
        }
        if (!EconomyMenus.titleContains(client, "аукцион", "auction")) {
            return;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        this.ownedContainerId = menu.containerId;
        OptionalLong price = AuctionPriceScanner.competitivePrice(
                menu,
                Items.ENCHANTED_GOLDEN_APPLE,
                "зачарованное золотое яблоко",
                this.saleCount
        );
        if (price.isEmpty()) {
            return;
        }
        this.salePrice = price.getAsLong();
        closeOwned(client);
        this.machine.transition(State.LIST_AUCTION, tick);
    }

    private void listAuction(Minecraft client, LocalPlayer player, long tick) {
        if (client.gui.screen() != null
                || !this.commandCooldown.ready(tick)
                || !prepareSaleStack(player)) {
            if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
                finishCycle(client, tick, 100L);
            }
            return;
        }
        ServerAdapter adapter = ServerAdapters.current();
        adapter.auctionCommand()
                .flatMap(root -> EconomyCommands.auctionSell(root, this.salePrice))
                .ifPresentOrElse(command -> {
                    adapter.sendCommand(player, command);
                    this.commandCooldown.tryAcquire(tick, 100L);
                    this.saleConfirmed = false;
                    this.saleRejected = false;
                    this.machine.transition(State.WAIT_SALE_CONFIRMATION, tick);
                }, () -> finishCycle(client, tick, 100L));
    }

    private void waitSaleConfirmation(Minecraft client, long tick) {
        if (this.saleConfirmed || this.saleRejected
                || this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, this.saleRejected ? AUCTION_RETRY_TICKS : 40L);
        }
    }

    private boolean prepareSaleStack(LocalPlayer player) {
        if (this.saleCount <= 0) {
            return false;
        }
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.is(Items.ENCHANTED_GOLDEN_APPLE)
                && mainHand.getCount() == this.saleCount) {
            return true;
        }
        int menuSlot = InventoryUtil.findPlayerMenuSlot(
                player,
                stack -> stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                        && stack.getCount() == this.saleCount
        );
        if (menuSlot < 0 || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        if (this.originalSelectedSlot < 0) {
            this.originalSelectedSlot = player.getInventory().getSelectedSlot();
        }
        if (menuSlot >= 36 && menuSlot <= 44) {
            this.saleHotbarSlot = menuSlot - 36;
            return EconomyInventory.selectHotbar(player, this.saleHotbarSlot);
        }
        this.saleHotbarSlot = player.getInventory().getSelectedSlot();
        this.saleSwapMenuSlot = menuSlot;
        return InventoryUtil.swapWithHotbar(menuSlot, this.saleHotbarSlot);
    }

    private void restoreSaleStack(LocalPlayer player) {
        if (player != null && player.containerMenu == player.inventoryMenu) {
            if (this.saleSwapMenuSlot >= 0 && this.saleHotbarSlot >= 0) {
                InventoryUtil.swapWithHotbar(this.saleSwapMenuSlot, this.saleHotbarSlot);
            }
            if (this.originalSelectedSlot >= 0) {
                EconomyInventory.selectHotbar(player, this.originalSelectedSlot);
            }
        }
        this.originalSelectedSlot = -1;
        this.saleSwapMenuSlot = -1;
        this.saleHotbarSlot = -1;
    }

    private BlockPos findSignedChest(Minecraft client,
                                     LocalPlayer player,
                                     ChestKind kind) {
        return switch (kind) {
            case APPLE -> firstSignedChest(client, player, "яблок", "apple");
            case GOLD -> firstSignedChest(client, player, "золот", "gold");
            case CRAFTER -> firstSignedChest(client, player, "крафтер", "crafter");
        };
    }

    private BlockPos firstSignedChest(Minecraft client,
                                      LocalPlayer player,
                                      String... labels) {
        for (String label : labels) {
            BlockPos found = NearbyEconomyBlocks.nearestSignedChest(
                    client.level,
                    player,
                    SEARCH_RADIUS,
                    label
            );
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean canTakeResources() {
        return SynchronizationFeature.isActive() || this.takeCrafterChest.getValue();
    }

    private boolean ensureCycleResources(long tick) {
        if (this.resourcesClaimed) {
            return true;
        }
        if (!claim(
                AutomationResource.MOVEMENT,
                AutomationResource.NAVIGATION,
                AutomationResource.INVENTORY,
                AutomationResource.SCREEN,
                AutomationResource.CHAT
        )) {
            this.nextWorkTick = tick + 5L;
            return false;
        }
        this.resourcesClaimed = true;
        return true;
    }

    private void closeOwned(Minecraft client) {
        if (client.player != null
                && client.player.containerMenu instanceof CraftingMenu menu
                && menu.containerId == this.ownedContainerId
                && !this.crafting.cleanup(client, menu)) {
            return;
        }
        EconomyMenus.closeOwned(client, this.ownedContainerId);
        this.ownedContainerId = -1;
    }

    private void finishCycle(Minecraft client, long tick, long delayTicks) {
        closeOwned(client);
        this.navigator.close();
        restoreSaleStack(client.player);
        this.targetBlock = null;
        this.chestKind = null;
        this.pendingRecipe = null;
        this.saleCount = 0;
        this.salePrice = 0L;
        this.saleConfirmed = false;
        this.saleRejected = false;
        this.lootActions = 0;
        this.crafting.reset();
        this.machine.transition(State.WAIT, tick);
        this.nextWorkTick = tick + Math.max(1L, delayTicks);
        if (this.resourcesClaimed) {
            this.resourcesClaimed = false;
            PveAutomationCoordinator.INSTANCE.release(this);
        }
    }

    private void resetRuntime(boolean closeScreen) {
        Minecraft client = Minecraft.getInstance();
        if (closeScreen) {
            closeOwned(client);
            restoreSaleStack(client.player);
        }
        this.navigator.close();
        this.machine.reset(0L);
        this.actionCooldown.reset();
        this.commandCooldown.reset();
        this.crafting.reset();
        this.nextWorkTick = 0L;
        this.auctionRetryTick = 0L;
        this.targetBlock = null;
        this.chestKind = null;
        this.pendingRecipe = null;
        this.ownedContainerId = -1;
        if (this.resourcesClaimed) {
            this.resourcesClaimed = false;
            PveAutomationCoordinator.INSTANCE.release(this);
        }
        this.saleCount = 0;
        this.salePrice = 0L;
        this.originalSelectedSlot = -1;
        this.saleSwapMenuSlot = -1;
        this.saleHotbarSlot = -1;
        this.saleConfirmed = false;
        this.saleRejected = false;
        this.lootActions = 0;
        this.lastTick = 0L;
    }

    private static int largestStackCount(LocalPlayer player, net.minecraft.world.item.Item item) {
        int largest = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                largest = Math.max(largest, stack.getCount());
            }
        }
        return largest;
    }

    enum State {
        WAIT,
        SELECT,
        FIND_CHEST,
        MOVE_CHEST,
        OPEN_CHEST,
        LOOT_CHEST,
        FIND_TABLE,
        MOVE_TABLE,
        OPEN_TABLE,
        CRAFT,
        OPEN_AUCTION_SEARCH,
        WAIT_AUCTION_SEARCH,
        LIST_AUCTION,
        WAIT_SALE_CONFIRMATION
    }

    enum ChestKind {
        APPLE,
        GOLD,
        CRAFTER
    }
}
