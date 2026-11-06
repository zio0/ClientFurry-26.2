package win.furryhook.feature.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.event.events.input.PlayerInputEvent;
import win.furryhook.event.events.packet.PacketReceiveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.InputBindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.utils.InputGuard;
import win.furryhook.utils.combat.FireworkUse;
import win.furryhook.utils.text.ChatUtil;

public final class ElytraHelperFeature extends Feature {
    private static final int GLFW_PRESS = 1;
    private static final int GLFW_RELEASE = 0;
    private static final int CHEST_SLOT = 6;
    private static final int SWAP_COOLDOWN = 10;

    private static final Item[] CHESTPLATE_PRIORITY = {
            Items.NETHERITE_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.LEATHER_CHESTPLATE
    };

    public final InputBindSetting swapKey = register(new InputBindSetting("Swap Key", InputBindSetting.UNBOUND));
    public final ModeSetting bypassMode = register(new ModeSetting("Bypass", "Grim", "Grim", "FT | SP", "HolyWorld"));
    public final BooleanSetting equipOnLanding = register(new BooleanSetting("Equip On Landing", false));
    public final InputBindSetting fireworkKey = register(new InputBindSetting("Firework Key", InputBindSetting.UNBOUND));
    public final BooleanSetting autoFly = register(new BooleanSetting("Auto Fly", true));
    public final BooleanSetting autoFirework = register(new BooleanSetting("Auto Firework", false)
            .visibleWhen(this.autoFly::getValue));

    private boolean swapRequested;
    private boolean fireworkPending;
    private boolean hasFiredOnStart;
    private boolean queueJump;
    private boolean wasOnGround;
    private boolean wasInElytraFlight;

    private int tickSwap;
    private int swapCooldown;
    private int freezeTicks;
    private int lastElytraSlot = -1;

    private Minecraft mc;

    public ElytraHelperFeature() {
        super("ElytraHelper", "Elytra swap, firework and auto fly", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getAction() != GLFW_RELEASE && InputGuard.isBindingBlocked()) {
            return;
        }
        if (this.swapKey.matches(event.getKey())) {
            this.updateSwap(event.getAction());
        }
        if (this.fireworkKey.matches(event.getKey())) {
            this.updateFirework(event.getAction());
        }
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() != GLFW_RELEASE && InputGuard.isBindingBlocked()) {
            return;
        }
        if (this.swapKey.matchesMouse(event.getButton())) {
            this.updateSwap(event.getAction());
        }
        if (this.fireworkKey.matchesMouse(event.getButton())) {
            this.updateFirework(event.getAction());
        }
    }

    @EventTarget
    public void onInput(PlayerInputEvent event) {
        if (this.queueJump) {
            this.queueJump = false;
            event.setJump(true);
        }
        if (this.tickSwap > 0 || this.freezeTicks > 0) {
            event.clearMovement(false, false);
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        this.mc = mc;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            return;
        }

        if (this.tickSwap > 0) {
            this.tickSwap--;
        }
        if (this.swapCooldown > 0) {
            this.swapCooldown--;
        }
        if (this.freezeTicks > 0) {
            this.freezeTicks--;
        }

        if ((this.tickSwap > 0 || this.freezeTicks > 0) && !this.bypassMode.is("Grim")) {
            player.setSprinting(false);
            player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
        }

        if (this.swapRequested && this.tickSwap == 0) {
            boolean toChestplate = player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            this.swap(player, toChestplate);
            this.swapRequested = false;
            this.swapCooldown = SWAP_COOLDOWN;
            this.freezeTicks = this.bypassMode.is("FT | SP") ? 6 : this.bypassMode.is("HolyWorld") ? 4 : 3;
        }

        if (player.isFallFlying()) {
            this.wasInElytraFlight = true;
        }

        if (this.equipOnLanding.getValue()) {
            if (player.onGround() && !this.wasOnGround && this.wasInElytraFlight) {
                if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                    this.swap(player, true);
                }
                this.wasInElytraFlight = false;
            }
            this.wasOnGround = player.onGround();
        }

        if (player.onGround() || player.isInWater() || player.isInLava()) {
            this.hasFiredOnStart = false;
        }

        if (this.autoFly.getValue()) {
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            boolean wearingElytra = chest.is(Items.ELYTRA);
            boolean blocked = player.getAbilities().mayfly || player.isInWater() || player.isInLava()
                    || player.onClimbable() || player.isPassenger() || player.isSpectator();
            if (wearingElytra && !blocked) {
                if (player.onGround() && !player.isFallFlying() && !mc.options.keyJump.isDown()) {
                    this.queueJump = true;
                }
                if (!player.onGround() && !player.isFallFlying()) {
                    boolean started = player.tryToStartFallFlying();
                    if (mc.getConnection() != null) {
                        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                                player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    }
                    if (started) {
                        this.wasInElytraFlight = true;
                        if (this.autoFirework.getValue() && !this.hasFiredOnStart) {
                            this.fireworkPending = true;
                            this.hasFiredOnStart = true;
                        }
                    }
                }
            }
        }

        if (this.fireworkPending && player.isFallFlying()) {
            FireworkUse.use(player, false);
            this.fireworkPending = false;
            this.hasFiredOnStart = true;
        }

        FireworkUse.tick(player);
    }

    @EventTarget
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPhase() == PacketReceiveEvent.Phase.PRE
                && FireworkUse.suppressesSwing(event.getPacket())) {
            event.cancel();
        }
    }

    @Override
    protected void onDisable() {
        this.swapRequested = false;
        this.fireworkPending = false;
        this.hasFiredOnStart = false;
        this.queueJump = false;
        this.tickSwap = 0;
        this.swapCooldown = 0;
        this.freezeTicks = 0;
        this.lastElytraSlot = -1;
        FireworkUse.reset();
    }

    private void updateSwap(int action) {
        if (action != GLFW_PRESS || this.swapCooldown > 0) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA)) {
            if (this.findBestChestplate(player) < 0 && this.findFreeSlot(player) < 0) {
                return;
            }
        } else if (this.findSlot(player, Items.ELYTRA) < 0) {
            return;
        }
        this.tickSwap = 2;
        this.swapRequested = true;
    }

    private void updateFirework(int action) {
        if (action != GLFW_PRESS) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (player.isFallFlying()) {
            FireworkUse.use(player, false);
            this.hasFiredOnStart = true;
        } else {
            this.fireworkPending = true;
        }
    }

    private void swap(LocalPlayer player, boolean toChestplate) {
        switch (this.bypassMode.getValue()) {
            case "FT | SP", "HolyWorld" -> this.swapFrozen(player, toChestplate);
            default -> this.swapGrim(player, toChestplate);
        }
    }

    private void swapGrim(LocalPlayer player, boolean toChestplate) {
        if (toChestplate) {
            this.dequipGrim(player);
        } else {
            this.equipGrim(player);
        }
    }

    private void swapFrozen(LocalPlayer player, boolean toChestplate) {
        if (toChestplate) {
            this.dequipFrozen(player);
        } else {
            this.equipFrozen(player);
        }
    }

    private void equipGrim(LocalPlayer player) {
        int slot = this.findSlot(player, Items.ELYTRA);
        if (slot < 0) {
            ChatUtil.error("Elytra not found in inventory");
            return;
        }
        this.lastElytraSlot = -1;
        this.clickQuickMove(player, CHEST_SLOT);
        this.clickQuickMove(player, this.toScreenSlot(slot));
    }

    private void dequipGrim(LocalPlayer player) {
        int chestplateSlot = this.findBestChestplate(player);
        int freeSlot = chestplateSlot < 0 ? this.findFreeSlot(player) : -1;
        if (chestplateSlot < 0 && freeSlot < 0) {
            ChatUtil.error("Chestplate not found in inventory");
            return;
        }
        this.lastElytraSlot = -1;
        this.clickQuickMove(player, CHEST_SLOT);
        if (chestplateSlot >= 0) {
            this.clickQuickMove(player, this.toScreenSlot(chestplateSlot));
        }
    }

    private void equipFrozen(LocalPlayer player) {
        int slot = this.findSlot(player, Items.ELYTRA);
        if (slot < 0) {
            ChatUtil.error("Elytra not found in inventory");
            return;
        }
        if (slot < 9) {
            this.lastElytraSlot = slot;
            this.clickSwap(player, CHEST_SLOT, slot);
        } else {
            this.lastElytraSlot = 8;
            this.clickSwap(player, slot, 8);
            this.clickSwap(player, CHEST_SLOT, 8);
            this.clickSwap(player, slot, 8);
        }
    }

    private void dequipFrozen(LocalPlayer player) {
        int chestplateSlot = this.findBestChestplate(player);
        int freeSlot = chestplateSlot < 0 ? this.findFreeSlot(player) : -1;
        if (chestplateSlot < 0 && freeSlot < 0) {
            ChatUtil.error("Chestplate not found in inventory");
            return;
        }

        if (freeSlot >= 0) {
            if (freeSlot < 9) {
                this.clickSwap(player, CHEST_SLOT, freeSlot);
            } else {
                this.clickSwap(player, freeSlot, 8);
                this.clickSwap(player, CHEST_SLOT, 8);
                this.clickSwap(player, freeSlot, 8);
            }
            this.lastElytraSlot = -1;
            return;
        }

        int home = this.lastElytraSlot;
        if (home >= 0 && home <= 8) {
            if (chestplateSlot == home) {
                this.clickSwap(player, CHEST_SLOT, home);
                return;
            }
            this.clickSwap(player, this.toScreenSlot(chestplateSlot), home);
            this.clickSwap(player, CHEST_SLOT, home);
            return;
        }

        if (chestplateSlot < 9) {
            this.clickSwap(player, CHEST_SLOT, chestplateSlot);
            this.lastElytraSlot = chestplateSlot;
        } else {
            this.clickSwap(player, chestplateSlot, 8);
            this.clickSwap(player, CHEST_SLOT, 8);
            this.clickSwap(player, chestplateSlot, 8);
            this.lastElytraSlot = 8;
        }
    }

    private void clickQuickMove(LocalPlayer player, int slotId) {
        if (mc.gameMode == null || player.containerMenu == null) {
            return;
        }
        mc.gameMode.handleContainerInput(player.containerMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, player);
    }

    private void clickSwap(LocalPlayer player, int slotId, int hotbarButton) {
        if (mc.gameMode == null || player.containerMenu == null) {
            return;
        }
        mc.gameMode.handleContainerInput(player.containerMenu.containerId, slotId, hotbarButton, ContainerInput.SWAP, player);
    }

    private int toScreenSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
    }

    private int findSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private int findBestChestplate(LocalPlayer player) {
        for (Item item : CHESTPLATE_PRIORITY) {
            int slot = this.findSlot(player, item);
            if (slot >= 0) {
                return slot;
            }
        }
        return -1;
    }

    private int findFreeSlot(LocalPlayer player) {
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}