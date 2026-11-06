package win.furryhook.event.events.screen;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import win.furryhook.event.CancellableEvent;

@Getter
public final class ScreenCloseEvent extends CancellableEvent {
    private Minecraft client;
    private Screen screen;

    public ScreenCloseEvent set(Minecraft client, Screen screen) {
        this.client = client;
        this.screen = screen;
        return this;
    }
}
