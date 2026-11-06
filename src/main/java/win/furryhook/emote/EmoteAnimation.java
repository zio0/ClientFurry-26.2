package win.furryhook.emote;

import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A parsed emote timeline: for every (body part, axis) pair that any keyframe ever touches, a
 * sparse curve of (tick -> value, easing-into-this-point). Sampling a tick interpolates each
 * channel independently, so a keyframe that only sets {@code roll} on the torso doesn't disturb
 * whatever the last keyframed {@code x}/{@code y} on that same part was doing.
 */
public final class EmoteAnimation {
    public record ChannelPoint(float tick, float value, Easing easingIn) {
    }

    private final String id;
    private final String displayName;
    private final float beginTick;
    private final float endTick;
    private final float stopTick;
    private final boolean loop;
    private final Map<EmotePart, Map<Axis, NavigableMap<Float, ChannelPoint>>> channels;

    EmoteAnimation(String id, String displayName, float beginTick, float endTick, float stopTick, boolean loop,
                   Map<EmotePart, Map<Axis, NavigableMap<Float, ChannelPoint>>> channels) {
        this.id = id;
        this.displayName = displayName;
        this.beginTick = beginTick;
        this.endTick = Math.max(endTick, beginTick + 1.0F);
        this.stopTick = Math.max(stopTick, this.endTick);
        this.loop = loop;
        this.channels = channels;
    }

    public String id() {
        return this.id;
    }

    public String displayName() {
        return this.displayName;
    }

    public float beginTick() {
        return this.beginTick;
    }

    public float endTick() {
        return this.endTick;
    }

    public float stopTick() {
        return this.stopTick;
    }

    public boolean loop() {
        return this.loop;
    }

    /**
     * Duration of one non-looping playthrough, ticks. Callers advancing real time into "tick
     * space" (20 ticks/second) use this to decide when a one-shot emote has finished.
     */
    public float durationTicks() {
        return this.stopTick;
    }

    /**
     * Samples every animated channel at {@code tick}. Returns a map keyed by body part, each
     * value a 6-slot array indexed by {@link Axis#ordinal()}; {@link Float#NaN} marks an axis
     * this animation never touches on that part, meaning "leave the model's own pose alone".
     */
    public EnumMap<EmotePart, float[]> sample(float tick) {
        EnumMap<EmotePart, float[]> result = new EnumMap<>(EmotePart.class);
        for (Map.Entry<EmotePart, Map<Axis, NavigableMap<Float, ChannelPoint>>> partEntry : this.channels.entrySet()) {
            float[] values = new float[Axis.values().length];
            java.util.Arrays.fill(values, Float.NaN);
            boolean any = false;
            for (Map.Entry<Axis, NavigableMap<Float, ChannelPoint>> axisEntry : partEntry.getValue().entrySet()) {
                float sampled = sampleChannel(axisEntry.getValue(), tick);
                if (!Float.isNaN(sampled)) {
                    values[axisEntry.getKey().ordinal()] = sampled;
                    any = true;
                }
            }
            if (any) {
                result.put(partEntry.getKey(), values);
            }
        }
        return result;
    }

    private static float sampleChannel(NavigableMap<Float, ChannelPoint> channel, float tick) {
        if (channel.isEmpty()) {
            return Float.NaN;
        }
        Map.Entry<Float, ChannelPoint> floor = channel.floorEntry(tick);
        Map.Entry<Float, ChannelPoint> ceiling = channel.ceilingEntry(tick);
        if (floor == null) {
            return ceiling.getValue().value();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue().value();
        }
        ChannelPoint from = floor.getValue();
        ChannelPoint to = ceiling.getValue();
        float span = to.tick() - from.tick();
        if (span <= 0.0001F) {
            return to.value();
        }
        float progress = (tick - from.tick()) / span;
        float eased = to.easingIn().apply(progress);
        return from.value() + (to.value() - from.value()) * eased;
    }

    static final class Builder {
        private final Map<EmotePart, Map<Axis, TreeMap<Float, ChannelPoint>>> channels = new EnumMap<>(EmotePart.class);

        void put(EmotePart part, Axis axis, float tick, float value, Easing easingIn) {
            channels.computeIfAbsent(part, ignored -> new EnumMap<>(Axis.class))
                    .computeIfAbsent(axis, ignored -> new TreeMap<>())
                    .put(tick, new ChannelPoint(tick, value, easingIn));
        }

        Map<EmotePart, Map<Axis, NavigableMap<Float, ChannelPoint>>> build() {
            Map<EmotePart, Map<Axis, NavigableMap<Float, ChannelPoint>>> result = new EnumMap<>(EmotePart.class);
            for (var partEntry : channels.entrySet()) {
                Map<Axis, NavigableMap<Float, ChannelPoint>> axes = new EnumMap<>(Axis.class);
                axes.putAll(partEntry.getValue());
                result.put(partEntry.getKey(), axes);
            }
            return result;
        }
    }
}
