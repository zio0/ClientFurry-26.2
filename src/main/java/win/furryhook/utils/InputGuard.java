package win.furryhook.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Shared guard for features that listen to raw KeyboardInputEvent/MouseInputEvent directly
 * (bypassing FeatureManager's bind dispatch). Without this, client binds (AutoSwap, ElytraHelper,
 * ServerHelper quick-actions, etc.) fire while the player is typing in chat or in a focused text
 * field inside a container screen (creative search, anvil rename, sign edit), since the keybind
 * mixin hooks keyPress at HEAD, before the screen consumes it.
 */
public final class InputGuard {
    private InputGuard() {
    }

    public static boolean isBindingBlocked() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) {
            return false;
        }
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return true;
        }
        GuiEventListener focused = screen.getFocused();
        return focused instanceof EditBox editBox && editBox.isFocused();
    }
}
