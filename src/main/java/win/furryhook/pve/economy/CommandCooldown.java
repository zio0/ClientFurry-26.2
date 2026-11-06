package win.furryhook.pve.economy;

public final class CommandCooldown {
    private long nextTick;

    public boolean ready(long currentTick) {
        return currentTick >= this.nextTick;
    }

    public boolean tryAcquire(long currentTick, long cooldownTicks) {
        if (!ready(currentTick)) {
            return false;
        }
        this.nextTick = saturatedAdd(currentTick, Math.max(1L, cooldownTicks));
        return true;
    }

    public void defer(long currentTick, long delayTicks) {
        this.nextTick = Math.max(this.nextTick, saturatedAdd(currentTick, Math.max(1L, delayTicks)));
    }

    public void reset() {
        this.nextTick = 0L;
    }

    public long nextTick() {
        return this.nextTick;
    }

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
