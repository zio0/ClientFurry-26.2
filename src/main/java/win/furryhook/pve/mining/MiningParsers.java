package win.furryhook.pve.mining;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class MiningParsers {
    private static final Pattern IDENTIFIER =
            Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    private static final int MAX_VERTICAL_COORDINATE = 2_048;

    private MiningParsers() {
    }

    public static Set<String> identifiers(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split("[,;\\s]+")) {
            String id = token.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || !IDENTIFIER.matcher(id).matches()) {
                continue;
            }
            result.add(id.contains(":") ? id : "minecraft:" + id);
        }
        return Set.copyOf(result);
    }

    public static List<GridPoint> route(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<GridPoint> route = new ArrayList<>();
        for (String token : value.split("[;|]")) {
            parsePoint(token).ifPresent(route::add);
        }
        return List.copyOf(route);
    }

    public static Optional<GridPoint> point(String value) {
        return parsePoint(value);
    }

    public static Optional<Region> region(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] endpoints = value.trim().split("\\s*(?:->|:)\\s*", 2);
        if (endpoints.length != 2) {
            return Optional.empty();
        }
        Optional<GridPoint> first = parsePoint(endpoints[0]);
        Optional<GridPoint> second = parsePoint(endpoints[1]);
        if (first.isEmpty() || second.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Region(first.get(), second.get()));
    }

    private static Optional<GridPoint> parsePoint(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] components = value.trim().split("\\s*,\\s*");
        if (components.length != 3) {
            return Optional.empty();
        }
        try {
            int x = Integer.parseInt(components[0]);
            int y = Integer.parseInt(components[1]);
            int z = Integer.parseInt(components[2]);
            if (Math.abs((long) x) > MAX_HORIZONTAL_COORDINATE
                    || Math.abs((long) z) > MAX_HORIZONTAL_COORDINATE
                    || Math.abs((long) y) > MAX_VERTICAL_COORDINATE) {
                return Optional.empty();
            }
            return Optional.of(new GridPoint(x, y, z));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public record GridPoint(int x, int y, int z) {
        public BlockPos toBlockPos() {
            return new BlockPos(this.x, this.y, this.z);
        }
    }

    public record Region(GridPoint first, GridPoint second) {
        public boolean contains(BlockPos position) {
            return position.getX() >= Math.min(this.first.x, this.second.x)
                    && position.getX() <= Math.max(this.first.x, this.second.x)
                    && position.getY() >= Math.min(this.first.y, this.second.y)
                    && position.getY() <= Math.max(this.first.y, this.second.y)
                    && position.getZ() >= Math.min(this.first.z, this.second.z)
                    && position.getZ() <= Math.max(this.first.z, this.second.z);
        }
    }
}
