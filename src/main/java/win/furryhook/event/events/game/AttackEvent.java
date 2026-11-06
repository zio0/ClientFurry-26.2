package win.furryhook.event.events.game;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import win.furryhook.event.CancellableEvent;

@Getter
public final class AttackEvent extends CancellableEvent {
    private Minecraft client;

    public AttackEvent set(Minecraft client) {
        this.client = client;
        return this;
    }
}
