package win.furryhook.pve.economy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import win.furryhook.mixin.accessor.PlayerTabOverlayAccessor;

import java.util.Locale;

public final class ServerUiText {
    private ServerUiText() {
    }

    public static String tabHeader(Minecraft client) {
        if (client == null || client.gui == null || client.gui.hud.getTabList() == null) {
            return "";
        }
        Component header = ((PlayerTabOverlayAccessor) client.gui.hud.getTabList()).getHeader();
        return header == null ? "" : EconomyTextParser.normalize(header.getString());
    }

    public static String serverHost(Minecraft client) {
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server == null || server.ip == null) {
            return "";
        }
        String host = server.ip.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            return closing > 0 ? host.substring(1, closing) : host;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
