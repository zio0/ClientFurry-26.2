package win.furryhook.context;

import net.minecraft.client.multiplayer.ClientLevel;

public interface WorldContext extends MinecraftContext {
    default ClientLevel world() {
        return level();
    }

    default boolean hasWorld() {
        return world() != null;
    }
}
