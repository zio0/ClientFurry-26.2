package win.furryhook.feature.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.InputBindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.hud.NotificationsElement;
import win.furryhook.utils.InputGuard;

public final class AutoSwapFeature extends Feature {
    private static final int GLFW_RELEASE = 0;
    private static final int OFFHAND_BUTTON = 40;
    private static final String[] ITEM_NAMES = {"Totem of Undying", "Player Head", "Golden Apple", "Shield"};

    public final ModeSetting mode = register(new ModeSetting("Mode", "Legit", "Instant", "Legit", "FunTime", "Custom"));
    public final InputBindSetting swapKey = register(new InputBindSetting("Key", InputBindSetting.UNBOUND));
    public final ModeSetting first = register(new ModeSetting("First Item", "Totem of Undying", ITEM_NAMES));
    public final ModeSetting second = register(new ModeSetting("Second Item", "Player Head", ITEM_NAMES));

    public final BooleanSetting stopMovement = register(new BooleanSetting("Stop Movement", true).visibleWhen(this::custom));
    public final BooleanSetting stopSprint = register(new BooleanSetting("Stop Sprint", true).visibleWhen(this::custom));
    public final BooleanSetting closeInventory = register(new BooleanSetting("Close Inventory", true).visibleWhen(this::custom));
    public final NumberSetting preSwapMin = register(new NumberSetting("Pre-Swap Min", 20.0, 0.0, 300.0, 1.0, " ms")
            .visibleWhen(this::custom));
    public final NumberSetting preSwapMax = register(new NumberSetting("Pre-Swap Max", 100.0, 0.0, 300.0, 1.0, " ms")
            .visibleWhen(this::custom));

    /**
     * Ticks the player is pinned in place on each side of the click, in FunTime mode.
     *
     * <p>The other modes zero the velocity on the same tick they click, which is not a stop — the
     * movement packet for that tick has the player still walking, and FunTime refuses a container
     * click that arrives with motion behind it. Nothing happens and the swap looks broken.
     *
     * <p>Here the keys are actually taken away first, held long enough for the server to see a
     * stationary player, and only then is the slot clicked; the hold continues afterwards so the
     * next step out is not in the same tick as the click.
     */
    public final NumberSetting stopTicksBefore = register(new NumberSetting(
            "Stop Before", 4.0, 0.0, 20.0, 1.0, " ticks"
    ).visibleWhen(this::funTime));
    public final NumberSetting stopTicksAfter = register(new NumberSetting(
            "Stop After", 3.0, 0.0, 20.0, 1.0, " ticks"
    ).visibleWhen(this::funTime));

    /** Set while the FunTime sequence owns the player; read by KeyboardInputMixin. */
    private static volatile boolean holdingStill;

    private boolean wasHeld;
    private long pendingAt;

    /** Ticks left in the current leg of the FunTime sequence, and whether the click already went. */
    private int holdTicks;
    private boolean clicked;

    public static boolean shouldStopMovement() {
        return holdingStill;
    }

    public AutoSwapFeature() {
        super("AutoSwap", "Configurable offhand swap on key press", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getAction() != GLFW_RELEASE && InputGuard.isBindingBlocked()) {
            return;
        }
        if (this.swapKey.matches(event.getKey())) {
            updateHeld(event.getAction());
        }
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() != GLFW_RELEASE && InputGuard.isBindingBlocked()) {
            return;
        }
        if (this.swapKey.matchesMouse(event.getButton())) {
            updateHeld(event.getAction());
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft mc = event.getClient();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            return;
        }
        if (holdingStill) {
            tickFunTimeHold(mc, player);
            return;
        }
        if (this.pendingAt != 0 && System.currentTimeMillis() >= this.pendingAt) {
            this.pendingAt = 0;
            if (funTime()) {
                beginFunTimeHold(player);
            } else {
                performSwap(mc, player);
            }
        }
    }

    /**
     * Takes the keys away and freezes the player before anything is clicked. The velocity is zeroed
     * every tick of the hold, not once: a single zeroing is undone by the very next movement tick,
     * and it is the sustained standstill the server is looking for.
     */
    private void beginFunTimeHold(LocalPlayer player) {
        holdingStill = true;
        this.clicked = false;
        this.holdTicks = Math.max(0, this.stopTicksBefore.getValue().intValue());
        player.setSprinting(false);
        player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
    }

    private void tickFunTimeHold(Minecraft mc, LocalPlayer player) {
        player.setSprinting(false);
        player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);

        if (this.holdTicks > 0) {
            this.holdTicks--;
            return;
        }
        if (!this.clicked) {
            this.clicked = true;
            performSwap(mc, player);
            this.holdTicks = Math.max(0, this.stopTicksAfter.getValue().intValue());
            return;
        }
        holdingStill = false;
        this.clicked = false;
    }

    @Override
    protected void onDisable() {
        this.wasHeld = false;
        this.pendingAt = 0;
        holdingStill = false;
        this.holdTicks = 0;
        this.clicked = false;
    }

    private void updateHeld(int action) {
        boolean pressed = action != GLFW_RELEASE;
        if (pressed && !this.wasHeld) {
            this.pendingAt = System.currentTimeMillis() + delayBeforeSwap();
        }
        this.wasHeld = pressed;
    }

    private void performSwap(Minecraft mc, LocalPlayer player) {
        Item target = player.getOffhandItem().is(resolve(this.first.getValue()))
                ? resolve(this.second.getValue())
                : resolve(this.first.getValue());
        int slot = findSlot(player, target);
        if (slot < 0) {
            return;
        }

        boolean custom = custom();
        if (custom && this.stopSprint.getValue()) {
            player.setSprinting(false);
        }
        if (custom && this.stopMovement.getValue()) {
            player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
        }

        int inventorySlot = slot >= 36 ? slot - 36 : slot;
        ItemStack swapped = player.getInventory().getItem(inventorySlot);
        Component itemName = swapped.isEmpty() ? target.getName(target.getDefaultInstance()) : swapped.getHoverName();

        mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot, OFFHAND_BUTTON, ContainerInput.SWAP, player);
        NotificationsElement.notify(NotificationsElement.NotificationType.SUCCESS, "Swapped to ", itemName);

        if (custom && this.closeInventory.getValue() && mc.gui.screen() != null) {
            mc.gui.setScreen(null);
        }
    }

    private long delayBeforeSwap() {
        return switch (this.mode.getValue()) {
            case "Instant" -> 0L;
            case "Legit" -> 75L;
            // FunTime does its waiting in ticks around the click, not in milliseconds before it.
            case "FunTime" -> 0L;
            default -> Math.round((this.preSwapMin.getValue() + this.preSwapMax.getValue()) * 0.5);
        };
    }

    private boolean custom() {
        return this.mode.is("Custom");
    }

    private boolean funTime() {
        return this.mode.is("FunTime");
    }

    private int findSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                return i < 9 ? i + 36 : i;
            }
        }
        return -1;
    }

    private Item resolve(String name) {
        return switch (name) {
            case "Player Head" -> Items.PLAYER_HEAD;
            case "Golden Apple" -> Items.GOLDEN_APPLE;
            case "Shield" -> Items.SHIELD;
            default -> Items.TOTEM_OF_UNDYING;
        };
    }
}
