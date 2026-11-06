package win.furryhook.pve.mining;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class MiningTargetSelector {
    public enum DiggingMode {
        EVERYONE,
        ORE_PRIORITY,
        ONLY_ORE
    }

    private MiningTargetSelector() {
    }

    public static <T> Optional<Candidate<T>> select(
            List<Candidate<T>> candidates,
            DiggingMode mode,
            boolean throughWalls
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        DiggingMode resolvedMode = mode == null ? DiggingMode.EVERYONE : mode;
        return candidates.stream()
                .filter(Candidate::safe)
                .filter(Candidate::inWorkArea)
                .filter(candidate -> throughWalls || candidate.visible())
                .filter(candidate -> resolvedMode != DiggingMode.ONLY_ORE || candidate.ore())
                .min(comparator(resolvedMode));
    }

    public static <T> Optional<Candidate<T>> easierNeighbor(
            Candidate<T> primary,
            List<Candidate<T>> neighbors,
            boolean throughWalls
    ) {
        if (primary == null || neighbors == null || neighbors.isEmpty()) {
            return Optional.empty();
        }
        return neighbors.stream()
                .filter(Candidate::safe)
                .filter(Candidate::inWorkArea)
                .filter(candidate -> throughWalls || candidate.visible())
                .filter(candidate -> candidate.hardness() >= 0.0F)
                .filter(candidate -> candidate.hardness() < primary.hardness())
                .min(Comparator.<Candidate<T>>comparingDouble(Candidate::hardness)
                        .thenComparingDouble(Candidate::distanceSquared));
    }

    private static <T> Comparator<Candidate<T>> comparator(DiggingMode mode) {
        return Comparator
                .<Candidate<T>>comparingInt(candidate ->
                        mode == DiggingMode.ORE_PRIORITY && candidate.ore() ? 0 : 1)
                .thenComparingInt(candidate -> verticalRank(candidate.verticalOffset()))
                .thenComparingDouble(Candidate::distanceSquared);
    }

    private static int verticalRank(int offset) {
        if (offset == 0) {
            return 0;
        }
        return offset > 0 ? 1 : 2;
    }

    public record Candidate<T>(
            T value,
            boolean safe,
            boolean ore,
            boolean visible,
            boolean inWorkArea,
            int verticalOffset,
            double distanceSquared,
            float hardness
    ) {
    }
}
