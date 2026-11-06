package win.furryhook.utils.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

public final class LocalPlayerHistory {
    private static final int CAPACITY = 16;

    public record Snapshot(
            Vec3 pos,
            Vec3 motion,
            boolean onGround,
            boolean verticalCollision,
            boolean verticalCollisionBelow,
            float fallDistance
    ) {
    }

    private static final Snapshot[] BUFFER = new Snapshot[CAPACITY];
    private static int head;
    private static int size;

    private LocalPlayerHistory() {
    }

    public static void record(LocalPlayer player) {
        head = (head + 1) % CAPACITY;
        BUFFER[head] = new Snapshot(
                player.position(),
                player.getDeltaMovement(),
                player.onGround(),
                player.verticalCollision,
                player.verticalCollisionBelow,
                (float) player.fallDistance
        );
        size = Math.min(size + 1, CAPACITY);
    }

    public static Snapshot get(int ticksAgo) {
        if (ticksAgo < 0 || ticksAgo >= size) {
            return null;
        }
        return BUFFER[(head - ticksAgo % CAPACITY + CAPACITY) % CAPACITY];
    }

    public static boolean verticalCollision(int ticksAgo, boolean fallback) {
        Snapshot snapshot = get(ticksAgo);
        return snapshot == null ? fallback : snapshot.verticalCollision();
    }

    public static boolean verticalCollisionBelow(int ticksAgo, boolean fallback) {
        Snapshot snapshot = get(ticksAgo);
        return snapshot == null ? fallback : snapshot.verticalCollisionBelow();
    }

    public static void reset() {
        for (int i = 0; i < CAPACITY; i++) {
            BUFFER[i] = null;
        }
        head = 0;
        size = 0;
    }
}
