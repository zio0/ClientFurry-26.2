package win.furryhook.pve.economy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

public final class EconomyChat {
    private EconomyChat() {
    }

    public static String incomingText(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket systemChat) {
            return systemChat.content().getString();
        }
        if (packet instanceof ClientboundDisguisedChatPacket disguisedChat) {
            return disguisedChat.message().getString();
        }
        if (packet instanceof ClientboundPlayerChatPacket playerChat) {
            Component unsigned = playerChat.unsignedContent();
            return unsigned == null ? playerChat.body().content() : unsigned.getString();
        }
        return null;
    }
}
