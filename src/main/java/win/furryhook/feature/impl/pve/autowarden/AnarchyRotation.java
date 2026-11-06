package win.furryhook.feature.impl.pve.autowarden;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnarchyRotation {
    private static final double DEATH_COST = 24.0D;
    private static final double FAILED_CYCLE_COST = 8.0D;
    private static final double DEPOSIT_VALUE = 4.0D;
    private static final double CHEST_VALUE = 2.0D;
    private static final double EXPLORATION_VALUE = 12.0D;

    private final Map<Integer, MutableMetrics> metrics = new HashMap<>();
    private int active = -1;
    private int tieCursor;

    public int active() {
        return this.active;
    }

    public int pickNext(List<Integer> candidates, double crowdPenalty, long nowMillis) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            this.active = -1;
            return -1;
        }

        boolean hasAvailable = candidates.stream().anyMatch(value -> !isAvoided(value, nowMillis));
        int size = candidates.size();
        int selected = -1;
        double selectedScore = Double.NEGATIVE_INFINITY;
        for (int offset = 0; offset < size; offset++) {
            int index = Math.floorMod(this.tieCursor + offset, size);
            int candidate = candidates.get(index);
            if (hasAvailable && isAvoided(candidate, nowMillis)) {
                continue;
            }
            double score = score(candidate, crowdPenalty, nowMillis);
            if (score > selectedScore) {
                selected = candidate;
                selectedScore = score;
            }
        }

        this.active = selected;
        if (selected >= 0) {
            this.tieCursor = Math.floorMod(candidates.indexOf(selected) + 1, size);
            mutable(selected).visits++;
        }
        return selected;
    }

    public double score(int anarchy, double crowdPenalty, long nowMillis) {
        MutableMetrics value = mutable(anarchy);
        double crowd = value.crowdSamples == 0
                ? 0.0D
                : value.crowdTotal / (double) value.crowdSamples;
        double exploration = EXPLORATION_VALUE / (value.visits + 1.0D);
        double avoided = isAvoided(anarchy, nowMillis) ? 1_000_000.0D : 0.0D;
        return value.depositedItems * DEPOSIT_VALUE
                + value.lootedChests * CHEST_VALUE
                + exploration
                - value.deaths * DEATH_COST
                - value.failedCycles * FAILED_CYCLE_COST
                - crowd * Math.max(0.0D, crowdPenalty)
                - avoided;
    }

    public void onChestLooted(int anarchy) {
        if (anarchy >= 0) {
            mutable(anarchy).lootedChests++;
        }
    }

    public void onDeposited(int anarchy, int itemCount) {
        if (anarchy >= 0 && itemCount > 0) {
            MutableMetrics value = mutable(anarchy);
            value.depositedItems += itemCount;
            value.completedCycles++;
        }
    }

    public void onDeath(int anarchy) {
        if (anarchy >= 0) {
            mutable(anarchy).deaths++;
        }
    }

    public void onFailedCycle(int anarchy) {
        if (anarchy >= 0) {
            mutable(anarchy).failedCycles++;
        }
    }

    public void observeCrowd(int anarchy, int nearbyPlayers) {
        if (anarchy < 0) {
            return;
        }
        MutableMetrics value = mutable(anarchy);
        value.crowdTotal += Math.max(0, nearbyPlayers);
        value.crowdSamples++;
    }

    public void avoidFor(int anarchy, long durationMillis, long nowMillis) {
        if (anarchy >= 0) {
            mutable(anarchy).avoidUntil = Math.max(
                    mutable(anarchy).avoidUntil,
                    nowMillis + Math.max(0L, durationMillis)
            );
        }
    }

    public boolean isAvoided(int anarchy, long nowMillis) {
        MutableMetrics value = this.metrics.get(anarchy);
        return value != null && value.avoidUntil > nowMillis;
    }

    public Snapshot snapshot(int anarchy) {
        MutableMetrics value = mutable(anarchy);
        return new Snapshot(
                value.visits,
                value.deaths,
                value.lootedChests,
                value.depositedItems,
                value.completedCycles,
                value.failedCycles,
                value.crowdSamples == 0 ? 0.0D : value.crowdTotal / (double) value.crowdSamples,
                value.avoidUntil
        );
    }

    public Map<Integer, Snapshot> snapshots(Collection<Integer> anarchies) {
        Map<Integer, Snapshot> result = new LinkedHashMap<>();
        for (Integer anarchy : anarchies) {
            if (anarchy != null) {
                result.put(anarchy, snapshot(anarchy));
            }
        }
        return Map.copyOf(result);
    }

    public void reset() {
        this.metrics.clear();
        this.active = -1;
        this.tieCursor = 0;
    }

    private MutableMetrics mutable(int anarchy) {
        return this.metrics.computeIfAbsent(anarchy, ignored -> new MutableMetrics());
    }

    public record Snapshot(
            int visits,
            int deaths,
            int lootedChests,
            int depositedItems,
            int completedCycles,
            int failedCycles,
            double averageCrowd,
            long avoidUntilMillis
    ) {
    }

    private static final class MutableMetrics {
        private int visits;
        private int deaths;
        private int lootedChests;
        private int depositedItems;
        private int completedCycles;
        private int failedCycles;
        private long crowdTotal;
        private int crowdSamples;
        private long avoidUntil;
    }
}
