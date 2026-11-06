package win.furryhook.pve.server;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

public enum ServerProfile {
    GENERIC,
    FUNTIME,
    HOLYWORLD,
    REALLYWORLD;

    public static ServerProfile detect(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server == null) {
            return GENERIC;
        }
        return detect(server.name, server.ip);
    }

    public static ServerProfile detect(String name, String address) {
        String identity = ((name == null ? "" : name)
                + " "
                + (address == null ? "" : address)).toLowerCase(Locale.ROOT);
        if (identity.contains("funtime")) {
            return FUNTIME;
        }
        if (identity.contains("holyworld") || identity.contains("holy-world")) {
            return HOLYWORLD;
        }
        if (identity.contains("reallyworld") || identity.contains("spookytime")) {
            return REALLYWORLD;
        }
        return GENERIC;
    }
}
