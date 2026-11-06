package win.furryhook.feature.impl.pve.autowarden;

import win.furryhook.pve.server.ServerAdapter;
import win.furryhook.pve.server.ServerProfile;

import java.util.Optional;

final class FunTimeWardenContract {
    private final ServerAdapter adapter;

    FunTimeWardenContract(ServerAdapter adapter) {
        this.adapter = adapter;
    }

    boolean supported() {
        return this.adapter.profile() == ServerProfile.FUNTIME;
    }

    Optional<String> switchAnarchy(int number) {
        return supported() ? this.adapter.anarchyCommand(number) : Optional.empty();
    }

    Optional<String> home(String homeName) {
        return supported() ? this.adapter.homeCommand(homeName) : Optional.empty();
    }

    Optional<String> auction() {
        return supported() ? this.adapter.auctionCommand() : Optional.empty();
    }

    Optional<String> sell(long price) {
        return supported() && price > 0L
                ? Optional.of("ah sell " + price)
                : Optional.empty();
    }

    Optional<String> invest(long amount) {
        return supported() && amount > 0L
                ? Optional.of("clan invest " + amount)
                : Optional.empty();
    }

    Optional<String> report(String playerName) {
        return supported() ? this.adapter.reportCommand(playerName) : Optional.empty();
    }
}
