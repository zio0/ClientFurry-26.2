package win.furryhook.utils.combat;

import java.util.concurrent.ThreadLocalRandom;

public final class SprintManager {
    private static final long DEFAULT_HOLD_MS = 75L;
    private static final long MIN_HOLD_MS = 60L;
    private static final long MAX_HOLD_MS = 91L;

    private static long lastImminentNanos = Long.MIN_VALUE;
    private static long holdMillis = DEFAULT_HOLD_MS;

    private SprintManager() {
    }

    public static void markAttackImminent() {
        lastImminentNanos = System.nanoTime();
    }

    public static void onAttack() {
        holdMillis = ThreadLocalRandom.current().nextLong(MIN_HOLD_MS, MAX_HOLD_MS + 1L);
        lastImminentNanos = System.nanoTime();
    }

    public static boolean shouldFreezeMovementInput() {
        return lastImminentNanos != Long.MIN_VALUE
                && System.nanoTime() - lastImminentNanos < holdMillis * 1_000_000L;
    }

    public static void reset() {
        lastImminentNanos = Long.MIN_VALUE;
        holdMillis = DEFAULT_HOLD_MS;
    }
}
