package win.furryhook.feature.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.InputGuard;
import win.furryhook.utils.inventory.InventorySwap;
import win.furryhook.utils.inventory.InventoryUtil;

public final class WindHopFeature extends Feature {
    private static final int GLFW_RELEASE = 0;
    private static final int GLFW_PRESS = 1;

    public final InputBindSetting key = register(new InputBindSetting("Key", InputBindSetting.UNBOUND));
    public final BooleanSetting auto = register(new BooleanSetting("Auto", false));
    public final NumberSetting delay = register(new NumberSetting("Delay", 4.0, 1.0, 20.0, 1.0, " ticks"));

    private boolean held;
    private boolean pending;
    private int cooldown;

    public WindHopFeature() {
        super("WindHop", "Uses a wind charge for height on key press", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getAction() != GLFW_RELEASE && InputGuard.isBindingBlocked()) {
            return;
        }
        if (this.key.matches(event.getKey())) {
            updateHeld(event.getAction());
        }
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() != GLFW_RELEASE && InputGuard.isBindingBlocked()) {
            return;
        }
        if (this.key.matchesMouse(event.getButton())) {
            updateHeld(event.getAction());
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (this.cooldown > 0) {
            this.cooldown--;
        }

        boolean trigger = this.auto.getValue() ? this.held : this.pending;
        if (!trigger) {
            return;
        }
        this.pending = false;
        useWindCharge();
    }

    @Override
    protected void onDisable() {
        this.held = false;
        this.pending = false;
        this.cooldown = 0;
    }

    private void updateHeld(int action) {
        if (action == GLFW_PRESS) {
            this.held = true;
            this.pending = true;
        } else if (action == GLFW_RELEASE) {
            this.held = false;
        }
    }

    private void useWindCharge() {
        if (this.cooldown > 0 || InventorySwap.isBusy()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int slot = InventoryUtil.findPlayerMenuSlot(player, stack -> stack.is(Items.WIND_CHARGE));
        if (slot < 0) {
            return;
        }
        InventorySwap.useFromSlot(slot);
        this.cooldown = Math.max(1, Math.round(this.delay.getValue().floatValue()));
    }
}
