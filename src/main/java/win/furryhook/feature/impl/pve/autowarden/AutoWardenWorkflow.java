package win.furryhook.feature.impl.pve.autowarden;

import win.furryhook.pve.PveStateMachine;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AutoWardenWorkflow {
    public enum Phase {
        IDLE,
        ENSURE_ANARCHY,
        HOME_TO_WARDEN_CITY,
        SEARCH_CHEST,
        PATROL_CITY,
        MOVE_TO_CHEST,
        HOME_WAIT,
        RETURN_TO_CITY,
        OPEN_AND_LOOT,
        PVP_HIDE,
        GO_TO_STORAGE,
        DEPOSIT_LOOT,
        RESTOCK,
        SELL_ITEMS
    }

    private static final Map<Phase, Set<Phase>> ALLOWED = allowedTransitions();
    private static final Map<Phase, Long> DEFAULT_TIMEOUT_TICKS = Map.ofEntries(
            Map.entry(Phase.IDLE, Long.MAX_VALUE),
            Map.entry(Phase.ENSURE_ANARCHY, 500L),
            Map.entry(Phase.HOME_TO_WARDEN_CITY, 500L),
            Map.entry(Phase.SEARCH_CHEST, 600L),
            Map.entry(Phase.PATROL_CITY, 3_600L),
            Map.entry(Phase.MOVE_TO_CHEST, 1_200L),
            Map.entry(Phase.HOME_WAIT, 12_000L),
            Map.entry(Phase.RETURN_TO_CITY, 1_200L),
            Map.entry(Phase.OPEN_AND_LOOT, 600L),
            Map.entry(Phase.PVP_HIDE, 3_600L),
            Map.entry(Phase.GO_TO_STORAGE, 3_600L),
            Map.entry(Phase.DEPOSIT_LOOT, 1_800L),
            Map.entry(Phase.RESTOCK, 1_800L),
            Map.entry(Phase.SELL_ITEMS, 12_000L)
    );

    private final PveStateMachine<Phase> machine = new PveStateMachine<>(Phase.IDLE);
    private String lastReason = "Disabled";

    public Phase phase() {
        return this.machine.state();
    }

    public long enteredTick() {
        return this.machine.enteredTick();
    }

    public long ticksInPhase(long tick) {
        return this.machine.ticksInState(tick);
    }

    public long transitionCount() {
        return this.machine.transitionCount();
    }

    public String lastReason() {
        return this.lastReason;
    }

    public boolean start(boolean validConfiguration, boolean needsStorage, long tick) {
        if (!validConfiguration) {
            this.lastReason = "Waiting for valid anarchy settings";
            return false;
        }
        return move(needsStorage ? Phase.GO_TO_STORAGE : Phase.ENSURE_ANARCHY,
                needsStorage ? "Supplies or stored loot require home" : "Starting loot rotation",
                tick);
    }

    public boolean anarchyReady(long tick) {
        return move(Phase.HOME_TO_WARDEN_CITY, "Loot anarchy selected", tick);
    }

    public boolean cityReady(long tick) {
        return move(Phase.SEARCH_CHEST, "Warden city reached", tick);
    }

    public boolean chestSelected(int remainingSeconds, int homeWaitThresholdSeconds, long tick) {
        Phase next = remainingSeconds > Math.max(0, homeWaitThresholdSeconds)
                ? Phase.HOME_WAIT
                : Phase.MOVE_TO_CHEST;
        return move(next, next == Phase.HOME_WAIT
                ? "Waiting safely for chest timer"
                : "Approaching selected chest", tick);
    }

    public boolean noChestFound(long tick) {
        return move(Phase.PATROL_CITY, "No suitable chest is currently known", tick);
    }

    public boolean patrolFoundChest(long tick) {
        return move(Phase.MOVE_TO_CHEST, "Patrol discovered a chest", tick);
    }

    public boolean returnWindowReached(long tick) {
        return move(Phase.RETURN_TO_CITY, "Chest return window reached", tick);
    }

    public boolean chestReached(long tick) {
        return move(Phase.OPEN_AND_LOOT, "Chest is in interaction range", tick);
    }

    public boolean continueSearching(long tick) {
        return move(Phase.SEARCH_CHEST, "Searching for another chest", tick);
    }

    public boolean requestStorage(String reason, long tick) {
        return move(Phase.GO_TO_STORAGE, reason, tick);
    }

    public boolean storageReady(long tick) {
        return move(Phase.DEPOSIT_LOOT, "Storage reached", tick);
    }

    public boolean depositFinished(boolean needsRestock, boolean autoSell, long tick) {
        Phase next = needsRestock ? Phase.RESTOCK : autoSell ? Phase.SELL_ITEMS : Phase.IDLE;
        return move(next, needsRestock
                ? "Restocking supplies"
                : autoSell ? "Selling deposited loot" : "Cycle complete", tick);
    }

    public boolean restockFinished(boolean autoSell, long tick) {
        return move(autoSell ? Phase.SELL_ITEMS : Phase.IDLE,
                autoSell ? "Supplies restored; selling loot" : "Cycle complete",
                tick);
    }

    public boolean sellFinished(long tick) {
        return move(Phase.IDLE, "Selling complete", tick);
    }

    public boolean dangerDetected(long tick) {
        if (this.machine.is(Phase.PVP_HIDE)) {
            return false;
        }
        this.lastReason = "Avoiding nearby danger";
        return this.machine.transition(Phase.PVP_HIDE, tick);
    }

    public boolean dangerCleared(boolean carryLoot, long tick) {
        return move(carryLoot ? Phase.GO_TO_STORAGE : Phase.SEARCH_CHEST,
                carryLoot ? "Danger ended; preserving loot" : "Danger ended",
                tick);
    }

    public boolean rotateAnarchy(long tick) {
        return move(Phase.ENSURE_ANARCHY, "Rotating to another loot anarchy", tick);
    }

    public boolean move(Phase next, String reason, long tick) {
        Objects.requireNonNull(next, "next");
        Phase current = this.machine.state();
        if (current == next) {
            return false;
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(next)) {
            return false;
        }
        this.lastReason = reason == null || reason.isBlank() ? next.name() : reason;
        return this.machine.transition(next, tick);
    }

    public boolean timedOut(long tick, long overrideTimeoutTicks) {
        long timeout = overrideTimeoutTicks > 0L
                ? overrideTimeoutTicks
                : DEFAULT_TIMEOUT_TICKS.getOrDefault(phase(), 1_200L);
        return timeout != Long.MAX_VALUE && ticksInPhase(tick) >= timeout;
    }

    public boolean timedOut(long tick) {
        return timedOut(tick, -1L);
    }

    public void failSafe(String reason, long tick) {
        this.lastReason = reason == null || reason.isBlank() ? "Stopped safely" : reason;
        this.machine.transition(Phase.IDLE, tick);
    }

    public void reset(long tick) {
        this.machine.reset(tick);
        this.lastReason = "Disabled";
    }

    private static Map<Phase, Set<Phase>> allowedTransitions() {
        EnumMap<Phase, Set<Phase>> result = new EnumMap<>(Phase.class);
        result.put(Phase.IDLE, EnumSet.of(Phase.ENSURE_ANARCHY, Phase.GO_TO_STORAGE));
        result.put(Phase.ENSURE_ANARCHY, EnumSet.of(
                Phase.HOME_TO_WARDEN_CITY, Phase.GO_TO_STORAGE, Phase.IDLE, Phase.PVP_HIDE
        ));
        result.put(Phase.HOME_TO_WARDEN_CITY, EnumSet.of(
                Phase.SEARCH_CHEST, Phase.GO_TO_STORAGE, Phase.ENSURE_ANARCHY, Phase.IDLE, Phase.PVP_HIDE
        ));
        result.put(Phase.SEARCH_CHEST, EnumSet.of(
                Phase.PATROL_CITY, Phase.MOVE_TO_CHEST, Phase.HOME_WAIT, Phase.GO_TO_STORAGE,
                Phase.ENSURE_ANARCHY, Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.PATROL_CITY, EnumSet.of(
                Phase.SEARCH_CHEST, Phase.MOVE_TO_CHEST, Phase.HOME_WAIT, Phase.GO_TO_STORAGE,
                Phase.ENSURE_ANARCHY, Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.MOVE_TO_CHEST, EnumSet.of(
                Phase.OPEN_AND_LOOT, Phase.SEARCH_CHEST, Phase.GO_TO_STORAGE,
                Phase.ENSURE_ANARCHY, Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.HOME_WAIT, EnumSet.of(
                Phase.RETURN_TO_CITY, Phase.GO_TO_STORAGE, Phase.ENSURE_ANARCHY,
                Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.RETURN_TO_CITY, EnumSet.of(
                Phase.MOVE_TO_CHEST, Phase.SEARCH_CHEST, Phase.GO_TO_STORAGE,
                Phase.ENSURE_ANARCHY, Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.OPEN_AND_LOOT, EnumSet.of(
                Phase.SEARCH_CHEST, Phase.GO_TO_STORAGE, Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.PVP_HIDE, EnumSet.of(
                Phase.SEARCH_CHEST, Phase.GO_TO_STORAGE, Phase.ENSURE_ANARCHY, Phase.IDLE
        ));
        result.put(Phase.GO_TO_STORAGE, EnumSet.of(
                Phase.DEPOSIT_LOOT, Phase.ENSURE_ANARCHY, Phase.PVP_HIDE, Phase.IDLE
        ));
        result.put(Phase.DEPOSIT_LOOT, EnumSet.of(
                Phase.RESTOCK, Phase.SELL_ITEMS, Phase.ENSURE_ANARCHY, Phase.IDLE, Phase.PVP_HIDE
        ));
        result.put(Phase.RESTOCK, EnumSet.of(
                Phase.SELL_ITEMS, Phase.ENSURE_ANARCHY, Phase.IDLE, Phase.PVP_HIDE
        ));
        result.put(Phase.SELL_ITEMS, EnumSet.of(
                Phase.ENSURE_ANARCHY, Phase.IDLE, Phase.PVP_HIDE
        ));
        return Map.copyOf(result);
    }
}
