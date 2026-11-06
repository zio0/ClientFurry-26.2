package win.furryhook.feature.impl.visual;

import org.lwjgl.glfw.GLFW;
import win.furryhook.context.MinecraftContext;
import win.furryhook.emote.EmoteWheel;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.InputBindSetting;
import win.furryhook.utils.InputGuard;

/**
 * Master switch for the emote wheel, plus its own "Wheel Bind" setting - separate from the
 * feature's own {@link #getBind() bind} (which just turns this module on/off, like any other
 * toggle). The wheel only responds to that setting's key while the module is enabled: turning
 * the feature off unsubscribes it from input entirely (see {@link Feature#setEnabled}), so the
 * bind naturally goes dead with it - no separate "is this on" check needed.
 */
public final class EmotionsFeature extends Feature implements MinecraftContext {
    public final InputBindSetting wheelBind = register(new InputBindSetting("Wheel Bind", InputBindSetting.UNBOUND));

    public EmotionsFeature() {
        super("Emotions", "Radial emote wheel", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        if (EmoteWheel.isOpen()) {
            EmoteWheel.requestCancel();
            EmoteWheel.close(mc);
        }
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (!this.wheelBind.matches(event.getKey()) || InputGuard.isBindingBlocked()) {
            return;
        }
        handleBindAction(event.getAction());
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (!this.wheelBind.matchesMouse(event.getButton()) || InputGuard.isBindingBlocked()) {
            return;
        }
        handleBindAction(event.getAction());
    }

    private void handleBindAction(int action) {
        if (action == GLFW.GLFW_PRESS) {
            EmoteWheel.open();
        } else if (action == GLFW.GLFW_RELEASE) {
            EmoteWheel.close(mc);
        }
    }
}
