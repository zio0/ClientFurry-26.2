package win.furryhook.pve.server;

import net.minecraft.client.Minecraft;
import win.furryhook.feature.impl.pve.PveManagerFeature;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class ServerAdapters {
    private static final Map<ServerProfile, ServerAdapter> ADAPTERS =
            new EnumMap<>(ServerProfile.class);

    static {
        ADAPTERS.put(ServerProfile.GENERIC, new GenericAdapter());
        ADAPTERS.put(ServerProfile.FUNTIME, new FunTimeAdapter());
        ADAPTERS.put(ServerProfile.HOLYWORLD, new HolyWorldAdapter());
        ADAPTERS.put(ServerProfile.REALLYWORLD, new ReallyWorldAdapter());
    }

    private ServerAdapters() {
    }

    public static ServerAdapter current() {
        return forProfile(PveManagerFeature.INSTANCE.resolveServerProfile(
                Minecraft.getInstance()
        ));
    }

    public static ServerAdapter forProfile(ServerProfile profile) {
        return ADAPTERS.getOrDefault(profile, ADAPTERS.get(ServerProfile.GENERIC));
    }

    private static class GenericAdapter implements ServerAdapter {
        @Override
        public ServerProfile profile() {
            return ServerProfile.GENERIC;
        }
    }

    private static final class FunTimeAdapter extends GenericAdapter {
        @Override
        public ServerProfile profile() {
            return ServerProfile.FUNTIME;
        }

        @Override
        public Optional<String> anarchyCommand(int number) {
            return number > 0 ? Optional.of("an " + number) : Optional.empty();
        }

        @Override
        public Optional<String> homeCommand(String home) {
            return home == null || home.isBlank()
                    ? Optional.of("home")
                    : Optional.of("home " + home.trim());
        }

        @Override
        public Optional<String> hubCommand() {
            return Optional.of("hub");
        }

        @Override
        public Optional<String> auctionCommand() {
            return Optional.of("ah");
        }

        @Override
        public Optional<String> reportCommand(String playerName) {
            return playerName == null || playerName.isBlank()
                    ? Optional.empty()
                    : Optional.of("report " + playerName.trim());
        }
    }

    private static final class HolyWorldAdapter extends GenericAdapter {
        @Override
        public ServerProfile profile() {
            return ServerProfile.HOLYWORLD;
        }
    }

    private static final class ReallyWorldAdapter extends GenericAdapter {
        @Override
        public ServerProfile profile() {
            return ServerProfile.REALLYWORLD;
        }

        @Override
        public Optional<String> hubCommand() {
            return Optional.of("hub");
        }

        @Override
        public Optional<String> reportCommand(String playerName) {
            return playerName == null || playerName.isBlank()
                    ? Optional.empty()
                    : Optional.of("report " + playerName.trim());
        }
    }
}
