package win.furryhook.event.events.lifecycle;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import win.furryhook.event.Event;

@Getter
public final class ClientStartEvent extends Event {
    private Minecraft client;

    public ClientStartEvent set(Minecraft client) {
        this.client = client;
        return this;
    }
}
