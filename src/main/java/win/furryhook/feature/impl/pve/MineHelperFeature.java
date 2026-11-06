package win.furryhook.feature.impl.pve;

import win.furryhook.feature.FeatureCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.AttackEvent;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.PlayerInputEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.packet.PacketSendEvent;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveFeature;
import win.furryhook.pve.mining.MineTimer;
import win.furryhook.pve.mining.MiningInventory;
import win.furryhook.pve.mining.MiningParsers;
import win.furryhook.pve.mining.MiningServerAdapter;
import win.furryhook.pve.mining.MiningServerAdapters;
import win.furryhook.pve.mining.MiningToolProfile;
import win.furryhook.pve.navigation.BaritoneNavigator;
import win.furryhook.utils.inventory.DropAllInventoryController;
import win.furryhook.utils.inventory.InventorySwap;
import win.furryhook.utils.inventory.InventoryUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class MineHelperFeature extends PveFeature {
    private enum TrashDropState {
        IDLE,
        PREPARING,
        DROPPING,
        SETTLING
    }

    private static final String NEXT_MINE = "Next Mine";
    private static final String CLEAN_INVENTORY = "Clean Inventory";
    private static final String SAVE_PICKAXE = "Save Pickaxe";
    private static final int TIMER_SCAN_INTERVAL_TICKS = 60;

    public final MultiSelectSetting helpers = register(new MultiSelectSetting(
            "Helpers",
            Set.of(NEXT_MINE, CLEAN_INVENTORY, SAVE_PICKAXE),
            NEXT_MINE,
            CLEAN_INVENTORY,
            SAVE_PICKAXE
    ));
    public final NumberSetting cleanupAtFreeSlots = register(new NumberSetting(
            "Cleanup At Free Slots", 2.0D, 1.0D, 12.0D, 1.0D, ""
    ));
    public final NumberSetting dropInterval = register(new NumberSetting(
            "Drop Interval", 1.0D, 1.0D, 60.0D, 1.0D, " s"
    ).visibleWhen(() -> this.helpers.isSelected(CLEAN_INVENTORY)));
    public final TextSetting trashItems = register(new TextSetting(
            "Trash Items",
            "cobblestone,cobbled_deepslate,dirt,gravel,andesite,diorite,granite,tuff,netherrack,deepslate,stone",
            512
    ));

    private MineTimer currentTimer;
    private long tick;
    private long lastTrashActionTick;
    private boolean pickaxeProtected;
    private final Deque<Integer> trashDropSlots = new ArrayDeque<>();
    private Set<String> activeTrashItems = Set.of();
    private TrashDropState trashDropState = TrashDropState.IDLE;

    public MineHelperFeature() {
        super(
                "MineHelper",
                "Shows mine timers, removes configured trash and protects pickaxes",
                FeatureCategory.MISC,
                BindSetting.UNBOUND,
                AutomationPriority.BACKGROUND
        );
    }

    @Override
    protected void onPveEnable() {
        resetRuntime();
    }

    @Override
    protected void onPveDisable() {
        cancelTrashDrop();
        resetRuntime();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        cancelTrashDrop();
        resetRuntime();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft client = event.getClient();
        LocalPlayer player = client.player;
        this.tick++;
        if (player == null || client.level == null || client.gameMode == null) {
            cancelTrashDrop();
            this.pickaxeProtected = false;
            return;
        }
        if (this.trashDropState != TrashDropState.IDLE) {
            tickTrashDrop(client, player);
        }

        if (this.helpers.isSelected(NEXT_MINE)
                && (this.currentTimer == null
                || this.tick % TIMER_SCAN_INTERVAL_TICKS == 0L)) {
            updateMineTimer(client);
        }
        if (this.currentTimer != null
                && this.currentTimer.isExpired(System.currentTimeMillis())) {
            this.currentTimer = null;
        }

        this.pickaxeProtected = this.helpers.isSelected(SAVE_PICKAXE)
                && shouldProtect(player.getMainHandItem());
        if (this.pickaxeProtected) {
            client.gameMode.stopDestroyBlock();
        }

        if (this.trashDropState == TrashDropState.IDLE
                && this.helpers.isSelected(CLEAN_INVENTORY)
                && MiningInventory.freeSlots(player)
                <= this.cleanupAtFreeSlots.getValue().intValue()
                && this.tick - this.lastTrashActionTick >= dropIntervalTicks()) {
            tryClearTrash(client, player);
        }
    }

    @EventTarget
    public void onPlayerInput(PlayerInputEvent event) {
        if (this.trashDropState != TrashDropState.IDLE) {
            event.clearMovement(false, true);
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        LocalPlayer player = event.getClient().player;
        if (this.helpers.isSelected(SAVE_PICKAXE)
                && player != null
                && shouldProtect(player.getMainHandItem())) {
            this.pickaxeProtected = true;
            event.cancel();
            if (event.getClient().gameMode != null) {
                event.getClient().gameMode.stopDestroyBlock();
            }
        }
    }

    @EventTarget
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPhase() != PacketSendEvent.Phase.PRE
                || !this.helpers.isSelected(SAVE_PICKAXE)
                || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !shouldProtect(player.getMainHandItem())) {
            return;
        }
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            this.pickaxeProtected = true;
            event.cancel();
        }
    }

    public Optional<MineTimer> getCurrentTimer() {
        return Optional.ofNullable(this.currentTimer);
    }

    public boolean isMineTimerSelected() {
        return this.helpers.isSelected(NEXT_MINE);
    }

    public boolean isPickaxeProtected() {
        return this.pickaxeProtected;
    }

    public boolean tryClearTrash() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        return player != null && tryClearTrash(client, player);
    }

    private void updateMineTimer(Minecraft client) {
        MiningServerAdapter adapter = MiningServerAdapters.forProfile(
                PveManagerFeature.INSTANCE.resolveServerProfile(client)
        );
        List<String> lines = hologramLines(client);
        adapter.parseMineTimer(lines, System.currentTimeMillis())
                .ifPresent(timer -> this.currentTimer = timer);
    }

    private List<String> hologramLines(Minecraft client) {
        if (client.level == null) {
            return List.of();
        }
        List<ArmorStand> stands = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand stand
                    && stand.hasCustomName()
                    && stand.getCustomName() != null) {
                stands.add(stand);
            }
        }
        stands.sort(Comparator.comparingDouble(
                (ArmorStand stand) -> stand.getY()
        ).reversed());
        return stands.stream()
                .map(stand -> stand.getCustomName().getString())
                .toList();
    }

    private boolean tryClearTrash(Minecraft client, LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || client.gui.screen() != null
                || this.trashDropState != TrashDropState.IDLE
                || InventorySwap.isBusy()
                || DropAllInventoryController.blocksInventoryOperations()
                || !claim(AutomationResource.INVENTORY)) {
            return false;
        }
        this.activeTrashItems = MiningParsers.identifiers(
                this.trashItems.getValue()
        );
        this.trashDropSlots.clear();
        for (int slot = InventoryMenu.INV_SLOT_START;
             slot < InventoryMenu.USE_ROW_SLOT_END;
             slot++) {
            if (MiningInventory.isTrash(
                    player.inventoryMenu.getSlot(slot).getItem(),
                    this.activeTrashItems
            )) {
                this.trashDropSlots.addLast(slot);
            }
        }
        if (MiningInventory.isTrash(
                player.inventoryMenu.getSlot(InventoryMenu.SHIELD_SLOT).getItem(),
                this.activeTrashItems
        )) {
            this.trashDropSlots.addLast(InventoryMenu.SHIELD_SLOT);
        }
        if (this.trashDropSlots.isEmpty()) {
            this.lastTrashActionTick = this.tick;
            release(AutomationResource.INVENTORY);
            return false;
        }
        BaritoneNavigator.INSTANCE.cancel();
        client.gameMode.stopDestroyBlock();
        this.trashDropState = TrashDropState.PREPARING;
        return true;
    }

    private void tickTrashDrop(Minecraft client, LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || client.gui.screen() != null) {
            cancelTrashDrop();
            return;
        }
        client.gameMode.stopDestroyBlock();
        switch (this.trashDropState) {
            case PREPARING -> this.trashDropState = TrashDropState.DROPPING;
            case DROPPING -> {
                while (!this.trashDropSlots.isEmpty()) {
                    int slot = this.trashDropSlots.removeFirst();
                    if (!player.inventoryMenu.isValidSlotIndex(slot)
                            || !MiningInventory.isTrash(
                            player.inventoryMenu.getSlot(slot).getItem(),
                            this.activeTrashItems
                    )) {
                        continue;
                    }
                    InventoryUtil.dropPlayerStack(player, slot);
                    return;
                }
                this.trashDropState = TrashDropState.SETTLING;
            }
            case SETTLING -> finishTrashDrop();
            default -> {
            }
        }
    }

    private void finishTrashDrop() {
        if (this.trashDropState == TrashDropState.IDLE) {
            return;
        }
        this.trashDropSlots.clear();
        this.activeTrashItems = Set.of();
        this.trashDropState = TrashDropState.IDLE;
        this.lastTrashActionTick = this.tick;
        release(AutomationResource.INVENTORY);
    }

    private void cancelTrashDrop() {
        finishTrashDrop();
    }

    private boolean shouldProtect(ItemStack stack) {
        MiningToolProfile profile = MiningToolProfile.detect(
                PveManagerFeature.INSTANCE.resolveServerProfile(
                        Minecraft.getInstance()
                ),
                stack
        );
        return MiningInventory.isPickaxe(stack)
                && stack.isDamageableItem()
                && MiningInventory.remainingDurability(stack)
                <= profile.durabilityReserve(
                stack,
                PveManagerFeature.INSTANCE.minimumToolDurability.getValue()
        );
    }

    private long dropIntervalTicks() {
        return Math.max(20L, Math.round(this.dropInterval.getValue() * 20.0D));
    }

    private void resetRuntime() {
        this.currentTimer = null;
        this.tick = 0L;
        this.lastTrashActionTick = Long.MIN_VALUE / 2L;
        this.pickaxeProtected = false;
        this.trashDropSlots.clear();
        this.activeTrashItems = Set.of();
        this.trashDropState = TrashDropState.IDLE;
    }
}
