package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

public final class AutoTradeFeature extends PveFeature {
    private static final long MOVE_TIMEOUT_TICKS = 240L;
    private static final long OPEN_TIMEOUT_TICKS = 100L;
    private static final long AUCTION_RETRY_TICKS = 1_200L;

    public final BooleanSetting buyEmeralds = register(new BooleanSetting("Buy Emeralds", true));
    public final NumberSetting emeraldReserve = register(new NumberSetting(
            "Emerald Reserve",
            192.0,
            64.0,
            640.0,
            64.0,
            ""
    ));
    public final NumberSetting scanRadius = register(new NumberSetting(
            "Villager Radius",
            16.0,
            8.0,
            28.0,
            1.0,
            ""
    ));
    public final BooleanSetting depositGold = register(new BooleanSetting("Store Gold", true));
    public final NumberSetting chestScanRadius = register(new NumberSetting(
            "Chest Radius",
            16.0,
            4.0,
            32.0,
            1.0,
            ""
    ));
    public final TextSetting chestKeyword = register(
            new TextSetting("Chest Sign", "золото", 32)
                    .visibleWhen(this.depositGold::getValue)
    );
    public final BooleanSetting autoSellBlocks =
            register(new BooleanSetting("Sell Blocks", true));
    public final BooleanSetting craftBlocks =
            register(new BooleanSetting("Craft Blocks", true));
    public final TextSetting auctionQuery = register(
            new TextSetting("Auction Search", "золотой блок", 40)
                    .visibleWhen(this.autoSellBlocks::getValue)
    );
    public final NumberSetting restockCheck = register(new NumberSetting(
            "Restock Check",
            180.0,
            30.0,
            600.0,
            10.0,
            " s"
    ));

    private final PveStateMachine<State> machine = new PveStateMachine<>(State.WAIT);
    private final EconomyNavigator navigator =
            new EconomyNavigator(BaritoneNavigator.INSTANCE);
    private final CraftingMenuController crafting = new CraftingMenuController();
    private final CommandCooldown actionCooldown = new CommandCooldown();
    private final CommandCooldown commandCooldown = new CommandCooldown();
    private final Map<UUID, Long> exhaustedVillagers = new HashMap<>();

    private long lastTick;
    private long nextWorkTick;
    private long shopRetryTick;
    private long auctionRetryTick;
    private boolean resourcesClaimed;
    private boolean moneyDry;

    private Villager targetVillager;
    private BlockPos targetBlock;
    private int ownedContainerId = -1;
    private int selectedOffer = -1;
    private long offerSelectedTick;
    private int tradeActions;
    private int shopEmeraldBefore;
    private int depositActions;

    private int saleCount;
    private long salePrice;
    private int originalSelectedSlot = -1;
    private int saleSwapMenuSlot = -1;
    private int saleHotbarSlot = -1;
    private boolean saleConfirmed;
    private boolean saleRejected;

    public AutoTradeFeature() {
        super(
                "AutoTrade",
                "Buys emeralds, trades with clerics, and processes the resulting gold",
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
        this.exhaustedVillagers.entrySet().removeIf(entry -> tick >= entry.getValue());

        switch (this.machine.state()) {
            case WAIT -> {
                if (tick >= this.nextWorkTick) {
                    this.machine.transition(State.SELECT, tick);
                }
            }
            case SELECT -> selectWork(client, player, tick);
            case OPEN_SHOP -> openShop(client, player, tick);
            case WAIT_SHOP_MENU -> waitShopMenu(client, player, tick);
            case BUY_SHOP -> buyFromShop(client, player, tick);
            case FIND_VILLAGER -> findVillager(client, player, tick);
            case MOVE_VILLAGER -> moveVillager(player, tick);
            case OPEN_TRADE -> openTrade(client, player, tick);
            case TRADE -> trade(client, player, tick);
            case FIND_TABLE -> findTable(client, player, tick);
            case MOVE_TABLE -> moveBlock(player, State.OPEN_TABLE, tick);
            case OPEN_TABLE -> openTable(client, player, tick);
            case CRAFT_BLOCKS -> craftBlocks(client, tick);
            case FIND_DEPOSIT -> findDeposit(client, player, tick);
            case MOVE_DEPOSIT -> moveBlock(player, State.OPEN_DEPOSIT, tick);
            case OPEN_DEPOSIT -> openDeposit(client, player, tick);
            case DEPOSIT -> deposit(client, player, tick);
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
        Minecraft.getInstance().execute(() -> handleChat(normalized));
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
        int emeralds = EconomyInventory.count(player, Items.EMERALD);
        int ingots = EconomyInventory.count(player, Items.GOLD_INGOT);
        int blocks = EconomyInventory.count(player, Items.GOLD_BLOCK);

        switch (EconomyAutomationPolicy.tradeAction(
                emeralds,
                ingots,
                blocks,
                Math.toIntExact(Math.round(this.emeraldReserve.getValue())),
                this.buyEmeralds.getValue(),
                this.depositGold.getValue(),
                this.craftBlocks.getValue(),
                this.autoSellBlocks.getValue(),
                this.moneyDry,
                tick >= this.shopRetryTick,
                tick >= this.auctionRetryTick,
                largestStackCount(player, Items.GOLD_BLOCK) >= 64
        )) {
            case SELL_BLOCKS -> this.machine.transition(State.OPEN_AUCTION_SEARCH, tick);
            case CRAFT_BLOCKS -> this.machine.transition(State.FIND_TABLE, tick);
            case DEPOSIT_GOLD -> this.machine.transition(State.FIND_DEPOSIT, tick);
            case TRADE -> this.machine.transition(State.FIND_VILLAGER, tick);
            case BUY_EMERALDS -> this.machine.transition(State.OPEN_SHOP, tick);
            case WAIT -> finishCycle(client, tick, 40L);
        }
    }

    private void openShop(Minecraft client, LocalPlayer player, long tick) {
        if (!this.commandCooldown.tryAcquire(tick, 40L)) {
            return;
        }
        ServerAdapters.current().sendCommand(player, EconomyCommands.shop());
        this.machine.transition(State.WAIT_SHOP_MENU, tick);
    }

    private void waitShopMenu(Minecraft client, LocalPlayer player, long tick) {
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, 100L);
            return;
        }
        if (!EconomyMenus.titleContains(client, "магазин", "shop")
                || player.containerMenu == player.inventoryMenu) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int emerald = EconomyMenus.findContainerSlot(menu, stack ->
                stack.is(Items.EMERALD)
                        || EconomyItemText.containsAny(stack, "изумруд", "emerald")
        );
        if (emerald < 0) {
            return;
        }
        this.ownedContainerId = menu.containerId;
        this.shopEmeraldBefore = EconomyInventory.count(player, Items.EMERALD);
        if (EconomyMenus.click(client, menu, emerald, 0, ContainerInput.PICKUP)) {
            this.actionCooldown.tryAcquire(tick, 8L);
            this.machine.transition(State.BUY_SHOP, tick);
        }
    }

    private void buyFromShop(Minecraft client, LocalPlayer player, long tick) {
        int emeralds = EconomyInventory.count(player, Items.EMERALD);
        if (emeralds >= Math.round(this.emeraldReserve.getValue())) {
            this.moneyDry = false;
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
            return;
        }
        if (emeralds > this.shopEmeraldBefore) {
            this.shopEmeraldBefore = emeralds;
            this.actionCooldown.defer(tick, 8L);
        }
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS * 2L
                || player.containerMenu == player.inventoryMenu) {
            finishCycle(client, tick, 100L);
            return;
        }
        if (!this.actionCooldown.ready(tick)
                || !EconomyMenus.titleContains(client, "магазин", "shop")) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int buy = EconomyMenus.findContainerSlot(menu, stack ->
                EconomyItemText.containsAny(stack, "купить", "buy", "приобрести")
        );
        if (buy < 0) {
            return;
        }
        this.ownedContainerId = menu.containerId;
        if (EconomyMenus.click(client, menu, buy, 0, ContainerInput.PICKUP)) {
            this.actionCooldown.tryAcquire(tick, 10L);
        }
    }

    private void findVillager(Minecraft client, LocalPlayer player, long tick) {
        double radius = this.scanRadius.getValue();
        this.targetVillager = client.level.getEntitiesOfClass(
                        Villager.class,
                        player.getBoundingBox().inflate(radius),
                        villager -> villager.isAlive()
                                && villager.getVillagerData()
                                .profession()
                                .is(VillagerProfession.CLERIC)
                                && !this.exhaustedVillagers.containsKey(villager.getUUID())
                )
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (this.targetVillager == null) {
            if (this.depositGold.getValue()
                    && (EconomyInventory.count(player, Items.GOLD_INGOT) > 0
                    || EconomyInventory.count(player, Items.GOLD_BLOCK) > 0)) {
                this.machine.transition(State.FIND_DEPOSIT, tick);
            } else {
                finishCycle(client, tick, 100L);
            }
            return;
        }
        this.machine.transition(State.MOVE_VILLAGER, tick);
    }

    private void moveVillager(LocalPlayer player, long tick) {
        if (this.targetVillager == null || !this.targetVillager.isAlive()) {
            this.machine.transition(State.FIND_VILLAGER, tick);
            return;
        }
        if (player.distanceToSqr(this.targetVillager) <= 10.25) {
            this.navigator.cancel();
            this.machine.transition(State.OPEN_TRADE, tick);
            return;
        }
        this.navigator.moveTo(player, this.targetVillager.blockPosition(), 2);
        if (this.machine.ticksInState(tick) > MOVE_TIMEOUT_TICKS) {
            markVillagerExhausted(tick);
            this.machine.transition(State.FIND_VILLAGER, tick);
        }
    }

    private void openTrade(Minecraft client, LocalPlayer player, long tick) {
        if (player.containerMenu instanceof MerchantMenu menu) {
            this.ownedContainerId = menu.containerId;
            this.selectedOffer = -1;
            this.tradeActions = 0;
            this.machine.transition(State.TRADE, tick);
            return;
        }
        if (this.targetVillager == null
                || !this.targetVillager.isAlive()
                || this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            markVillagerExhausted(tick);
            this.machine.transition(State.FIND_VILLAGER, tick);
            return;
        }
        if (!this.actionCooldown.tryAcquire(tick, 10L)) {
            return;
        }
        client.gameMode.interact(
                player,
                this.targetVillager,
                new EntityHitResult(
                        this.targetVillager,
                        this.targetVillager.getBoundingBox().getCenter()
                ),
                InteractionHand.MAIN_HAND
        );
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void trade(Minecraft client, LocalPlayer player, long tick) {
        if (!(player.containerMenu instanceof MerchantMenu menu)
                || menu.containerId != this.ownedContainerId) {
            finishCycle(client, tick, 60L);
            return;
        }
        if (this.tradeActions >= 128) {
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
            return;
        }
        if (this.selectedOffer >= 0) {
            if (tick - this.offerSelectedTick < 3L) {
                return;
            }
            ItemStack result = menu.getSlot(2).getItem();
            if (isGold(result)) {
                if (EconomyMenus.quickMove(client, menu, 2)) {
                    this.tradeActions++;
                    this.selectedOffer = -1;
                    this.actionCooldown.defer(tick, 3L);
                }
                return;
            }
            if (tick - this.offerSelectedTick <= 40L) {
                return;
            }
            markVillagerExhausted(tick);
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
            return;
        }
        if (!this.actionCooldown.ready(tick)) {
            return;
        }
        int offer = bestOffer(menu, EconomyInventory.count(player, Items.EMERALD));
        if (offer < 0) {
            markVillagerExhausted(tick);
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
            return;
        }
        menu.setSelectionHint(offer);
        menu.tryMoveItems(offer);
        player.connection.send(new ServerboundSelectTradePacket(offer));
        this.selectedOffer = offer;
        this.offerSelectedTick = tick;
    }

    private static int bestOffer(MerchantMenu menu, int emeralds) {
        int best = -1;
        double bestValue = 0.0;
        for (int index = 0; index < menu.getOffers().size(); index++) {
            MerchantOffer offer = menu.getOffers().get(index);
            int cost = emeraldCost(offer);
            int gold = goldValue(offer.getResult());
            if (offer.isOutOfStock() || cost <= 0 || cost > emeralds || gold <= 0) {
                continue;
            }
            double value = gold / (double) cost;
            if (value > bestValue) {
                bestValue = value;
                best = index;
            }
        }
        return best;
    }

    private void findTable(Minecraft client, LocalPlayer player, long tick) {
        this.targetBlock = NearbyEconomyBlocks.nearestCraftingTable(
                client.level,
                player,
                Math.round(this.chestScanRadius.getValue().floatValue())
        );
        if (this.targetBlock == null) {
            finishCycle(client, tick, 100L);
            return;
        }
        this.machine.transition(State.MOVE_TABLE, tick);
    }

    private void findDeposit(Minecraft client, LocalPlayer player, long tick) {
        this.targetBlock = NearbyEconomyBlocks.nearestSignedChest(
                client.level,
                player,
                Math.round(this.chestScanRadius.getValue().floatValue()),
                this.chestKeyword.getValue()
        );
        if (this.targetBlock == null) {
            finishCycle(client, tick, 100L);
            return;
        }
        this.machine.transition(State.MOVE_DEPOSIT, tick);
    }

    private void moveBlock(LocalPlayer player, State next, long tick) {
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

    private void openTable(Minecraft client, LocalPlayer player, long tick) {
        if (player.containerMenu instanceof CraftingMenu menu) {
            this.ownedContainerId = menu.containerId;
            this.crafting.reset();
            this.machine.transition(State.CRAFT_BLOCKS, tick);
            return;
        }
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, 100L);
            return;
        }
        interactBlock(client, player, tick);
    }

    private void openDeposit(Minecraft client, LocalPlayer player, long tick) {
        if (player.containerMenu instanceof ChestMenu menu) {
            this.ownedContainerId = menu.containerId;
            this.depositActions = 0;
            this.machine.transition(State.DEPOSIT, tick);
            return;
        }
        if (this.machine.ticksInState(tick) > OPEN_TIMEOUT_TICKS) {
            finishCycle(client, tick, 100L);
            return;
        }
        interactBlock(client, player, tick);
    }

    private void interactBlock(Minecraft client, LocalPlayer player, long tick) {
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

    private void craftBlocks(Minecraft client, long tick) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)
                || menu.containerId != this.ownedContainerId) {
            finishCycle(client, tick, 60L);
            return;
        }
        if (!this.actionCooldown.ready(tick)) {
            return;
        }
        CraftingMenuController.Result result = this.crafting.tick(
                client,
                menu,
                CraftingMenuController.Recipe.GOLD_BLOCK
        );
        this.actionCooldown.tryAcquire(tick, 2L);
        if (result == CraftingMenuController.Result.CRAFTED) {
            closeOwned(client);
            this.machine.transition(State.SELECT, tick);
        } else if (result == CraftingMenuController.Result.FAILED) {
            this.crafting.cleanup(client, menu);
            finishCycle(client, tick, 100L);
        }
    }

    private void deposit(Minecraft client, LocalPlayer player, long tick) {
        if (!(player.containerMenu instanceof ChestMenu menu)
                || menu.containerId != this.ownedContainerId) {
            finishCycle(client, tick, 60L);
            return;
        }
        if (!this.actionCooldown.ready(tick)) {
            return;
        }
        int firstPlayerSlot = ContainerLootService.containerSlotCount(menu);
        int goldSlot = -1;
        for (int slotId = firstPlayerSlot; slotId < menu.slots.size(); slotId++) {
            if (menu.isValidSlotIndex(slotId) && isGold(menu.getSlot(slotId).getItem())) {
                goldSlot = slotId;
                break;
            }
        }
        if (goldSlot < 0 || this.depositActions++ >= 24) {
            this.moneyDry = false;
            closeOwned(client);
            finishCycle(client, tick, 100L);
            return;
        }
        if (EconomyMenus.quickMove(client, menu, goldSlot)) {
            this.actionCooldown.tryAcquire(tick, 3L);
        }
    }

    private void openAuctionSearch(Minecraft client, LocalPlayer player, long tick) {
        if (!this.commandCooldown.ready(tick)) {
            return;
        }
        ServerAdapter adapter = ServerAdapters.current();
        adapter.auctionCommand()
                .flatMap(root -> EconomyCommands.auctionSearch(
                        root,
                        this.auctionQuery.getValue()
                ))
                .ifPresentOrElse(command -> {
                    this.saleCount = largestStackCount(player, Items.GOLD_BLOCK);
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
                Items.GOLD_BLOCK,
                this.auctionQuery.getValue(),
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
        if (this.saleCount < 64) {
            return false;
        }
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.is(Items.GOLD_BLOCK) && mainHand.getCount() == this.saleCount) {
            return true;
        }
        int menuSlot = InventoryUtil.findPlayerMenuSlot(
                player,
                stack -> stack.is(Items.GOLD_BLOCK)
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

    private void handleChat(String text) {
        if (!isEnabled()) {
            return;
        }
        if (EconomyTextParser.containsAny(
                text,
                "недостаточно денег",
                "не хватает денег",
                "недостаточно средств",
                "insufficient funds",
                "not enough money"
        )) {
            long requested = EconomyTextParser.largestAmount(text).orElse(100_000L);
            SynchronizationFeature.requestMoney((int) Math.min(Integer.MAX_VALUE, requested));
            this.moneyDry = true;
            this.shopRetryTick = this.lastTick + 200L;
            closeOwned(Minecraft.getInstance());
            this.machine.transition(State.SELECT, this.lastTick);
            return;
        }
        if (EconomyTextParser.containsAny(
                text,
                "не удалось выставить",
                "хранилищ",
                "слот",
                "ah rent",
                "auction slots are full"
        )) {
            this.saleRejected = true;
            this.auctionRetryTick = this.lastTick + AUCTION_RETRY_TICKS;
        } else if (EconomyTextParser.containsAny(
                text,
                "выставлен на продажу",
                "listed for sale"
        )) {
            this.saleConfirmed = true;
            this.auctionRetryTick = 0L;
        }
    }

    private void markVillagerExhausted(long tick) {
        if (this.targetVillager != null) {
            long delay = Math.round(this.restockCheck.getValue() * 20.0);
            this.exhaustedVillagers.put(
                    this.targetVillager.getUUID(),
                    tick + Math.max(1L, delay)
            );
        }
        this.targetVillager = null;
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
        this.targetVillager = null;
        this.targetBlock = null;
        this.selectedOffer = -1;
        this.tradeActions = 0;
        this.depositActions = 0;
        this.saleCount = 0;
        this.salePrice = 0L;
        this.saleConfirmed = false;
        this.saleRejected = false;
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
        this.exhaustedVillagers.clear();
        this.nextWorkTick = 0L;
        this.shopRetryTick = 0L;
        this.auctionRetryTick = 0L;
        if (this.resourcesClaimed) {
            this.resourcesClaimed = false;
            PveAutomationCoordinator.INSTANCE.release(this);
        }
        this.moneyDry = false;
        this.targetVillager = null;
        this.targetBlock = null;
        this.ownedContainerId = -1;
        this.selectedOffer = -1;
        this.tradeActions = 0;
        this.depositActions = 0;
        this.saleCount = 0;
        this.salePrice = 0L;
        this.originalSelectedSlot = -1;
        this.saleSwapMenuSlot = -1;
        this.saleHotbarSlot = -1;
        this.saleConfirmed = false;
        this.saleRejected = false;
        this.lastTick = 0L;
    }

    private static int emeraldCost(MerchantOffer offer) {
        int cost = 0;
        if (offer.getCostA().is(Items.EMERALD)) {
            cost += offer.getCostA().getCount();
        }
        if (offer.getCostB().is(Items.EMERALD)) {
            cost += offer.getCostB().getCount();
        }
        return cost;
    }

    private static int goldValue(ItemStack stack) {
        if (stack.is(Items.GOLD_BLOCK)) {
            return stack.getCount() * 9;
        }
        if (stack.is(Items.GOLD_INGOT)) {
            return stack.getCount();
        }
        if (stack.is(Items.GOLD_NUGGET)) {
            return Math.max(1, stack.getCount() / 9);
        }
        return 0;
    }

    private static boolean isGold(ItemStack stack) {
        return stack.is(Items.GOLD_BLOCK)
                || stack.is(Items.GOLD_INGOT)
                || stack.is(Items.GOLD_NUGGET);
    }

    private static int largestStackCount(LocalPlayer player, Item item) {
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
        OPEN_SHOP,
        WAIT_SHOP_MENU,
        BUY_SHOP,
        FIND_VILLAGER,
        MOVE_VILLAGER,
        OPEN_TRADE,
        TRADE,
        FIND_TABLE,
        MOVE_TABLE,
        OPEN_TABLE,
        CRAFT_BLOCKS,
        FIND_DEPOSIT,
        MOVE_DEPOSIT,
        OPEN_DEPOSIT,
        DEPOSIT,
        OPEN_AUCTION_SEARCH,
        WAIT_AUCTION_SEARCH,
        LIST_AUCTION,
        WAIT_SALE_CONFIRMATION
    }
}
