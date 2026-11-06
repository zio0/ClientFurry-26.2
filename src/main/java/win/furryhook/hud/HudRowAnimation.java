package win.furryhook.hud;

/**
 * Per-row enter/exit transition used by list-style HUD elements (Cooldowns, StaffList, ...),
 * ported from divine's HUD row animation. The incoming curve overshoots slightly (soft bounce
 * in), the outgoing curve overshoots the other way (soft bounce out), driven by an inverted
 * cubic-bezier easing sampled via Newton iteration.
 */
public final class HudRowAnimation {
    private static final long DURATION_NANOS = 300_000_000L;

    private float value;
    private float startValue;
    private float targetValue;
    private long startedAtNanos;
    private boolean done = true;

    public float update(boolean shown, long nowNanos) {
        float nextTarget = shown ? 1.0f : 0.0f;
        if (Float.compare(nextTarget, targetValue) != 0) {
            sample(nowNanos);
            startValue = value;
            targetValue = nextTarget;
            startedAtNanos = nowNanos;
            done = false;
        }
        return sample(nowNanos);
    }

    public boolean isHidden() {
        return done && targetValue == 0.0f && value == 0.0f;
    }

    private float sample(long nowNanos) {
        if (done) {
            return value;
        }

        long elapsed = Math.max(0L, nowNanos - startedAtNanos);
        if (elapsed >= DURATION_NANOS) {
            value = targetValue;
            done = true;
            return value;
        }

        float time = elapsed / (float) DURATION_NANOS;
        float eased = targetValue > startValue
                ? cubicBezier(time, 0.45f, 1.45f, 0.49f, 1.15f)
                : cubicBezier(time, 0.62f, -0.16f, 0.80f, 0.37f);
        value = startValue + (targetValue - startValue) * eased;
        return value;
    }

    private static float cubicBezier(float progress, float x1, float y1, float x2, float y2) {
        if (progress <= 0.0f) return 0.0f;
        if (progress >= 1.0f) return 1.0f;

        float t = progress;
        for (int i = 0; i < 8; i++) {
            float x = bezier(t, x1, x2) - progress;
            float derivative = bezierDerivative(t, x1, x2);
            if (Math.abs(x) < 0.00001f || Math.abs(derivative) < 0.000001f) break;
            t = Math.max(0.0f, Math.min(1.0f, t - x / derivative));
        }
        return bezier(t, y1, y2);
    }

    private static float bezier(float t, float p1, float p2) {
        float inv = 1.0f - t;
        return 3.0f * inv * inv * t * p1 + 3.0f * inv * t * t * p2 + t * t * t;
    }

    private static float bezierDerivative(float t, float p1, float p2) {
        float inv = 1.0f - t;
        return 3.0f * inv * inv * p1
                + 6.0f * inv * t * (p2 - p1)
                + 3.0f * t * t * (1.0f - p2);
    }
}
