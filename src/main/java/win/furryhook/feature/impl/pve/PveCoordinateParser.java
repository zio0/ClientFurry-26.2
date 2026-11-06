package win.furryhook.feature.impl.pve;

import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Optional;

final class PveCoordinateParser {
    private PveCoordinateParser() {
    }

    static Optional<BlockPos> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals("auto")) {
            return Optional.empty();
        }

        normalized = normalized
                .replace("(", "")
                .replace(")", "")
                .replace("[", "")
                .replace("]", "");
        String[] parts = normalized.split("[,;\\s]+");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            ));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
