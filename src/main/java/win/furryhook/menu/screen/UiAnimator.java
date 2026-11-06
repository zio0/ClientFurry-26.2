package win.furryhook.menu.screen;

import win.furryhook.utils.math.MathUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-delta driven exponential smoother, the same maths the module menu runs on.
 * One instance per screen: {@link #tick()} once at the top of a render pass, then
 * {@link #value(String, float, float)} per animated element.
 */
public final class UiAnimator {
    private final Map<String, Float> values = new HashMap<>();

    private long lastNanos;
    private float delta = 1.0F / 60.0F;

    public void tick() {
        long now = System.nanoTime();
        this.delta = this.lastNanos == 0L
                ? 1.0F / 60.0F
                : MathUtil.clamp((now - this.lastNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
        this.lastNanos = now;
    }

    public float delta() {
        return this.delta;
    }

    public float value(String key, float target, float speed) {
        float current = this.values.getOrDefault(key, target);
        float step = 1.0F - (float) Math.exp(-this.delta * speed);
        current += (target - current) * step;
        if (Math.abs(target - current) < 0.0015F) {
            current = target;
        }
        this.values.put(key, current);
        return current;
    }

    public void reset() {
        this.values.clear();
        this.lastNanos = 0L;
    }

    public static float easeOutCubic(float x) {
        float inv = 1.0F - x;
        return 1.0F - inv * inv * inv;
    }
}
