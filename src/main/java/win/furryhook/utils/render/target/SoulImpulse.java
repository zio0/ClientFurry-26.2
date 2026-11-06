package win.furryhook.utils.render.target;

import net.minecraft.world.entity.Entity;

/**
 * One-shot channel from the aura's attack path to the soul renderer: a landed hit makes the
 * nearest soul dive into the target and flare. The renderer is created lazily by the world
 * effect list, so the combat side cannot hold a reference to it directly.
 */
public final class SoulImpulse {
    private static volatile int entityId = Integer.MIN_VALUE;
    private static volatile long stamp;

    private static long consumed;

    private SoulImpulse() {
    }

    public static void hit(Entity entity) {
        if (entity == null) {
            return;
        }
        entityId = entity.getId();
        stamp = System.nanoTime();
    }

    /** Returns true once per registered hit on {@code id}. */
    static boolean consume(int id) {
        long current = stamp;
        if (current == consumed || entityId != id) {
            return false;
        }
        consumed = current;
        return true;
    }

    static void clear() {
        consumed = stamp;
    }
}
