package win.furryhook.emote;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which entities are currently playing an emote and samples their pose each frame.
 * Playback runs on wall-clock time at the animation's native 20 ticks/second so it stays frame
 * rate independent; {@link #poseFor} is called once per render from
 * {@code AvatarRendererMixin} and is the only cross-thread-safe entry point (render happens on
 * the client thread, same as {@link #play}/{@link #stop}, so a plain map is enough).
 */
public final class EmoteEngine {
    public static final EmoteEngine INSTANCE = new EmoteEngine();

    private static final float TICKS_PER_SECOND = 20.0F;

    private record Playback(EmoteAnimation animation, long startNanos) {
    }

    private final Map<UUID, Playback> active = new ConcurrentHashMap<>();

    private EmoteEngine() {
    }

    public void play(UUID entity, EmoteDefinition definition) {
        if (entity == null || definition == null) {
            return;
        }
        this.active.put(entity, new Playback(definition.animation(), System.nanoTime()));
    }

    public void stop(UUID entity) {
        if (entity != null) {
            this.active.remove(entity);
        }
    }

    public boolean isPlaying(UUID entity) {
        return entity != null && this.active.containsKey(entity);
    }

    /** Sampled model pose for {@code entity} this frame, or {@code null} if nothing is playing. */
    public EnumMap<EmotePart, float[]> poseFor(UUID entity) {
        if (entity == null) {
            return null;
        }
        Playback playback = this.active.get(entity);
        if (playback == null) {
            return null;
        }

        EmoteAnimation animation = playback.animation();
        float elapsedTicks = (System.nanoTime() - playback.startNanos()) / 1_000_000_000.0F * TICKS_PER_SECOND;
        float tick = animation.beginTick() + elapsedTicks;

        if (tick >= animation.stopTick()) {
            if (animation.loop() && animation.endTick() > animation.beginTick()) {
                float span = animation.endTick() - animation.beginTick();
                float looped = animation.beginTick() + (tick - animation.beginTick()) % span;
                return animation.sample(looped);
            }
            this.active.remove(entity);
            return null;
        }

        return animation.sample(tick);
    }
}
