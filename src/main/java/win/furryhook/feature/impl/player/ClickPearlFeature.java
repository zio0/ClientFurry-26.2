package win.furryhook.feature.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.InputBindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.mixin.accessor.MultiPlayerGameModeAccessor;
import win.furryhook.pve.AutomationOwner;
import win.furryhook.pve.AutomationPriority;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.utils.inventory.InventorySwap;
import win.furryhook.utils.inventory.InventoryUtil;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;

public final class ClickPearlFeature extends Feature implements AutomationOwner {
    private static final int HOTBAR_SIZE = 9;

    public final InputBindSetting key = register(new InputBindSetting("Key", GLFW.GLFW_KEY_UNKNOWN));

    public final ModeSetting mode = register(new ModeSetting("Mode", "Instant", "Instant", "FunTime"));

    /**
     * Ticks the pearl sits in hand on each side of the throw, for both Instant and FunTime modes.
     *
     * <p>The swap cycle used to be three ticks end to end: take the pearl, throw it, put it back. On
     * some servers that is too fast — the held-slot change and the use arrive inside the same packet
     * burst and the server resolves the click against the slot that was held before it — so nothing
     * is thrown and the pearl is still in the inventory. Holding a few ticks on each side lets the
     * server catch up.
     */
    public final NumberSetting holdBeforeThrow = register(new NumberSetting(
            "Hold Before Throw", 2.0, 0.0, 20.0, 1.0, " ticks"
    ));
    public final NumberSetting holdAfterThrow = register(new NumberSetting(
            "Hold After Throw", 2.0, 0.0, 20.0, 1.0, " ticks"
    ));

    /**
     * Auto-stop after throwing pearl (in ticks). When set to > 0, automatically stops player movement
     * for the specified number of ticks after throwing the pearl.
     */
    public final NumberSetting autoStopTicks = register(new NumberSetting(
            "Auto Stop Ticks", 0.0D, 0.0D, 100.0D, 1.0D, " ticks"
    ));

    private boolean throwQueued;
    private boolean releasePending;
    private int autoStopTicksRemaining = 0;

    /** FunTime hotbar sequence: which slot is being held, where to go back, and how long is left. */
    private int pendingSlot = -1;
    private int previousSlot = -1;
    private int holdTicks;
    private boolean thrown;

    public ClickPearlFeature() {
        super("ClickPearl", "Quickly throws an ender pearl", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        throwQueued = false;
        releasePending = false;
        this.autoStopTicksRemaining = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && this.previousSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(this.previousSlot);
            if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                acc.invokerEnsureHasSentCarriedItem();
            }
        }
        this.pendingSlot = -1;
        this.previousSlot = -1;
        this.holdTicks = 0;
        this.thrown = false;
        PveAutomationCoordinator.INSTANCE.release(this);
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && key.matches(event.getKey())) {
            queueThrow();
        }
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && key.matchesMouse(event.getButton()) && queueThrow()) {
            event.cancel();
        }
    }

    private boolean queueThrow() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() == null
                && client.player != null
                && PveAutomationCoordinator.INSTANCE.acquire(
                this,
                AutomationPriority.EMERGENCY,
                EnumSet.of(AutomationResource.INVENTORY, AutomationResource.ROTATION)
        )) {
            throwQueued = true;
            return true;
        }
        return false;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        // Handle auto-stop ticks
        if (this.autoStopTicksRemaining > 0) {
            this.autoStopTicksRemaining--;
            Minecraft client = event.getClient();
            if (client.options != null) {
                client.options.keyUp.setDown(false);
                client.options.keyDown.setDown(false);
                client.options.keyLeft.setDown(false);
                client.options.keyRight.setDown(false);
                client.options.keyJump.setDown(false);
            }
        }
        
        if (this.pendingSlot != -1) {
            tickHotbarHold(event.getClient());
            return;
        }
        if (this.releasePending && !InventorySwap.isBusy()) {
            this.releasePending = false;
            PveAutomationCoordinator.INSTANCE.release(this);
        }
        if (!throwQueued) {
            return;
        }
        throwQueued = false;
        boolean asyncSwap = false;
        try {
            Minecraft client = event.getClient();
            LocalPlayer player = client.player;
            if (player == null || client.gameMode == null) {
                return;
            }

            if (player.getMainHandItem().is(Items.ENDER_PEARL)) {
                throwFromMainHand(client, player);
                startAutoStop();
                return;
            }

            int hotbarSlot = findPearlHotbarSlot(player);
            if (hotbarSlot != -1) {
                this.previousSlot = player.getInventory().getSelectedSlot();
                this.pendingSlot = hotbarSlot;
                this.thrown = false;
                this.holdTicks = this.mode.is("FunTime")
                        ? Math.max(0, this.holdBeforeThrow.getValue().intValue())
                        : 1;
                player.getInventory().setSelectedSlot(hotbarSlot);
                asyncSwap = true;
                return;
            }

            int containerSlot = findPearlContainerSlot(player);
            if (containerSlot != -1) {
                InventorySwap.useFromSlot(
                        containerSlot,
                        false,
                        false,
                        this.holdBeforeThrow.getValue().intValue(),
                        this.holdAfterThrow.getValue().intValue()
                );
                this.releasePending = true;
                asyncSwap = true;
                startAutoStop();
            }
        } finally {
            if (!asyncSwap) {
                PveAutomationCoordinator.INSTANCE.release(this);
            }
        }
    }

    /**
     * The FunTime hotbar sequence, one leg per tick: hold the selected slot, throw, hold again, put
     * the old slot back. The slot is re-asserted every tick because anything else touching the
     * hotbar in between would otherwise throw from the wrong hand.
     */
    private void tickHotbarHold(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            finishHotbarHold(null);
            return;
        }
        if (player.getInventory().getSelectedSlot() != this.pendingSlot) {
            player.getInventory().setSelectedSlot(this.pendingSlot);
        }

        if (this.holdTicks > 0) {
            this.holdTicks--;
            return;
        }
        if (!this.thrown) {
            this.thrown = true;
            throwFromMainHand(client, player);
            startAutoStop();
            this.holdTicks = this.mode.is("FunTime")
                    ? Math.max(0, this.holdAfterThrow.getValue().intValue())
                    : 1;
            return;
        }
        finishHotbarHold(player);
    }

    private void finishHotbarHold(LocalPlayer player) {
        if (player != null && this.previousSlot >= 0) {
            player.getInventory().setSelectedSlot(this.previousSlot);
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode instanceof MultiPlayerGameModeAccessor acc) {
                acc.invokerEnsureHasSentCarriedItem();
            }
        }
        this.pendingSlot = -1;
        this.previousSlot = -1;
        this.holdTicks = 0;
        this.thrown = false;
        PveAutomationCoordinator.INSTANCE.release(this);
    }

    private void throwFromMainHand(Minecraft client, LocalPlayer player) {
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private int findPearlHotbarSlot(LocalPlayer player) {
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (player.getInventory().getItem(slot).is(Items.ENDER_PEARL)) {
                return slot;
            }
        }
        return -1;
    }

    private int findPearlContainerSlot(LocalPlayer player) {
        return InventoryUtil.findInventorySlot(player, stack -> stack.is(Items.ENDER_PEARL));
    }

    private void startAutoStop() {
        int stopTicks = this.autoStopTicks.getValue().intValue();
        if (stopTicks > 0) {
            this.autoStopTicksRemaining = stopTicks;
        }
    }
}
