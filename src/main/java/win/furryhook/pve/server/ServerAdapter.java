package win.furryhook.pve.server;

import net.minecraft.client.player.LocalPlayer;

import java.util.Optional;

public interface ServerAdapter {
    ServerProfile profile();

    default void sendCommand(LocalPlayer player, String command) {
        if (player == null || command == null || command.isBlank()) {
            return;
        }
        String normalized = command.charAt(0) == '/' ? command.substring(1) : command;
        player.connection.sendCommand(normalized);
    }

    default Optional<String> anarchyCommand(int number) {
        return Optional.empty();
    }

    default Optional<String> homeCommand(String home) {
        return Optional.empty();
    }

    default Optional<String> hubCommand() {
        return Optional.empty();
    }

    default Optional<String> auctionCommand() {
        return Optional.empty();
    }

    default Optional<String> reportCommand(String playerName) {
        return Optional.empty();
    }
}
