package win.furryhook.menu;

import win.furryhook.context.MinecraftContext;
import win.furryhook.emote.EmoteWheel;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.CharacterInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.event.events.screen.ScreenKeyEvent;
import win.furryhook.event.events.screen.ScreenMouseButtonEvent;
import win.furryhook.menu.core.MenuOverlay;
import org.lwjgl.glfw.GLFW;

public final class MenuKeyHandler implements MinecraftContext {
    @EventTarget(priority = 1_000)
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getKey() == GLFW.GLFW_KEY_F11) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            if (MenuOverlay.toggle(mc)) {
                event.cancel();
            }
            return;
        }

        // Doesn't touch the Emotions module's enabled state - only the wheel's own open/hovered
        // state, so Escape can dismiss it without switching the module off.
        if (event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_ESCAPE && EmoteWheel.isOpen()) {
            EmoteWheel.requestCancel();
            EmoteWheel.close(mc);
            event.cancel();
            return;
        }

        if (!MenuOverlay.isOpen()) {
            return;
        }

        boolean pressOrRepeat = event.getAction() == GLFW.GLFW_PRESS || event.getAction() == GLFW.GLFW_REPEAT;
        if (pressOrRepeat && MenuOverlay.handleKey(event.getKey())) {
            event.cancel();
            return;
        }

        if (event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            MenuOverlay.close(mc);
            event.cancel();
            return;
        }

        event.cancel();
    }

    @EventTarget(priority = 1_000)
    public void onCharacterInput(CharacterInputEvent event) {
        if (MenuOverlay.handleCharacter(event.getCodePoint())) {
            event.cancel();
        }
    }

    @EventTarget(priority = 1_000)
    public void onMouseInput(MouseInputEvent event) {
        if (MenuOverlay.handleMouseButton(mc, event.getButton(), event.getAction())) {
            event.cancel();
        }
    }

    @EventTarget(priority = 1_000)
    public void onScreenKey(ScreenKeyEvent event) {
        if (event.getKeyEvent() != null && event.getKeyEvent().key() == GLFW.GLFW_KEY_F11) {
            return;
        }
        if (MenuOverlay.blocksInput()) {
            event.cancel();
        }
    }

    @EventTarget(priority = 1_000)
    public void onScreenMouseButton(ScreenMouseButtonEvent event) {
        if (MenuOverlay.handleScreenMouseButton(mc, event.getMouseButtonEvent().button(), event.getAction())) {
            event.cancel();
        }
    }
}
