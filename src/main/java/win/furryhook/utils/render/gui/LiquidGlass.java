package win.furryhook.utils.render.gui;

import win.furryhook.utils.math.MathUtil;

/**
 * Tuning and pointer light for liquid glass surfaces.
 *
 * <p>The effect is opt-in per draw ({@link Render2DUtil.RectBuilder#liquid(float)}); plain
 * {@code glass()} panels are untouched, so the HUD keeps its flat frosted look.
 */
public final class LiquidGlass {

    /** Bevel is derived from the corner radius, clamped so tiny chips stay readable. */
    private static final float BEVEL_FROM_RADIUS = 1.15F;
    private static final float MIN_BEVEL = 5.0F;
    private static final float MAX_BEVEL = 22.0F;

    private static boolean enabled = true;
    private static float intensity = 1.0F;
    private static float refraction = 1.0F;
    private static float specular = 0.55F;
    private static float dispersion = 0.55F;
    private static float frost = 0.30F;

    private static float pointerX;
    private static float pointerY;
    private static boolean pointerValid;

    private LiquidGlass() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Master multiplier over every liquid term; 0 collapses back to plain glass. */
    public static float intensity() {
        return intensity;
    }

    public static void setIntensity(float value) {
        intensity = MathUtil.clamp(value, 0.0F, 1.0F);
    }

    public static float specular() {
        return specular;
    }

    public static void setSpecular(float value) {
        specular = MathUtil.clamp(value, 0.0F, 1.0F);
    }

    public static float dispersion() {
        return dispersion;
    }

    public static void setDispersion(float value) {
        dispersion = MathUtil.clamp(value, 0.0F, 1.0F);
    }

    public static float frost() {
        return frost;
    }

    public static void setFrost(float value) {
        frost = MathUtil.clamp(value, 0.0F, 1.0F);
    }

    public static float refraction() {
        return refraction;
    }

    public static void setRefraction(float value) {
        refraction = MathUtil.clamp(value, 0.0F, 2.0F);
    }

    /** Cursor position in GUI space; every panel lights up from wherever it is. */
    public static void setPointer(float x, float y) {
        pointerX = x;
        pointerY = y;
        pointerValid = true;
    }

    public static void clearPointer() {
        pointerValid = false;
    }

    /** Bevel width in the same units the rect was submitted in. */
    public static float bevelFor(float cornerRadius) {
        float bevel = Math.max(cornerRadius, 0.0F) * BEVEL_FROM_RADIUS;
        return MathUtil.clamp(bevel, MIN_BEVEL, MAX_BEVEL) * refraction;
    }

    /**
     * Light direction for a panel centred at the given point: unit vector pointing at the
     * cursor, or {@code 0} when there is no pointer, which lets the shader fall back to its
     * fixed upper-left key light.
     */
    public static float lightX(float centerX, float centerY) {
        return component(centerX, centerY, true);
    }

    public static float lightY(float centerX, float centerY) {
        return component(centerX, centerY, false);
    }

    private static float component(float centerX, float centerY, boolean horizontal) {
        if (!pointerValid) {
            return 0.0F;
        }
        float dx = pointerX - centerX;
        float dy = pointerY - centerY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.001F) {
            return 0.0F;
        }
        return (horizontal ? dx : dy) / length;
    }
}
