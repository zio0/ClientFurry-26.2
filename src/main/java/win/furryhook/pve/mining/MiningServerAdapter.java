package win.furryhook.pve.mining;

import java.util.List;
import java.util.Optional;

public interface MiningServerAdapter {
    default Optional<MineTimer> parseMineTimer(List<String> hologramLines, long nowMillis) {
        return Optional.empty();
    }

    default Optional<String> warpMineCommand() {
        return Optional.empty();
    }

    default Optional<String> homeCommand(String homeName) {
        return Optional.empty();
    }

    default Optional<String> anarchyCommand(int number) {
        return Optional.empty();
    }

    default Optional<String> randomTeleportCommand(String size) {
        return Optional.empty();
    }
}
