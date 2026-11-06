package win.furryhook.feature.impl.movement;

import net.minecraft.world.phys.Vec3;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * 1:1 port of Minced's {@code SuperFirework}. Replaces vanilla's flat 1.5/1.5 firework
 * boost with per-mode XZ/Y speed math driven by the player's folded yaw/pitch (0..45°).
 *
 * Modes:
 *  - Custom      — 9 XZ sliders (0-5°..40-45°) + 9 Y sliders, uses max(XZ, Y) on the XZ axis
 *  - Bravo       — big yaw/pitch decision table for XZ, pitch-only table for Y
 *  - BravoGrief  — fixed 9-slot XZ + Y arrays, indexed by folded angle / 5
 *  - ReallyWorld — same shape as BravoGrief with a different curve, XZ = max(XZ, Y)
 *
 * The actual add-to-motion step lives in
 * {@link win.furryhook.mixin.player.FireworkRocketEntityMixin}, which calls
 * {@link #computeBoost(float, float)} and applies Minced's addBoost formula.
 */
public final class ElytraBoostFeature extends Feature {

    private static final long BOOST_WINDOW_MS = 150L;

    public final ModeSetting mode = register(new ModeSetting(
            "Boost Mode",
            "BravoGrief",
            "Custom", "Bravo", "BravoGrief", "ReallyWorld"
    ));

    public final BooleanSetting nearBoost = register(new BooleanSetting(
            "Near Player Boost", false
    ));

    private final NumberSetting cXZ00 = register(slider("XZ 0-5",   1.52D));
    private final NumberSetting cXZ05 = register(slider("XZ 5-10",  1.53D));
    private final NumberSetting cXZ10 = register(slider("XZ 10-15", 1.54D));
    private final NumberSetting cXZ15 = register(slider("XZ 15-20", 1.55D));
    private final NumberSetting cXZ20 = register(slider("XZ 20-25", 1.56D));
    private final NumberSetting cXZ25 = register(slider("XZ 25-30", 1.57D));
    private final NumberSetting cXZ30 = register(slider("XZ 30-35", 1.58D));
    private final NumberSetting cXZ35 = register(slider("XZ 35-40", 1.59D));
    private final NumberSetting cXZ40 = register(slider("XZ 40-45", 1.60D));

    private final NumberSetting cY00 = register(slider("Y 0-5",   1.51D));
    private final NumberSetting cY05 = register(slider("Y 5-10",  1.52D));
    private final NumberSetting cY10 = register(slider("Y 10-15", 1.53D));
    private final NumberSetting cY15 = register(slider("Y 15-20", 1.54D));
    private final NumberSetting cY20 = register(slider("Y 20-25", 1.55D));
    private final NumberSetting cY25 = register(slider("Y 25-30", 1.56D));
    private final NumberSetting cY30 = register(slider("Y 30-35", 1.57D));
    private final NumberSetting cY35 = register(slider("Y 35-40", 1.58D));
    private final NumberSetting cY40 = register(slider("Y 40-45", 1.59D));

    private NumberSetting slider(String name, double def) {
        return new NumberSetting(name, def, 1.50D, 3.00D, 0.01D, "")
                .visibleWhen(() -> mode.is("Custom"));
    }

    private static final double[] BRAVOGRIEF_XZ = {1.67D, 1.68D, 1.69D, 1.72D, 1.72D, 1.73D, 1.74D, 1.75D, 1.76D};
    private static final double[] BRAVOGRIEF_Y  = {1.72D, 1.72D, 1.72D, 1.72D, 1.72D, 1.73D, 1.74D, 1.76D, 1.80D};
    private static final double[] REALLYWORLD_XZ = {1.60D, 1.63D, 1.66D, 1.71D, 1.73D, 1.81D, 1.81D, 1.81D, 1.81D};
    private static final double[] REALLYWORLD_Y  = {1.60D, 1.62D, 1.60D, 1.62D, 1.64D, 1.68D, 1.75D, 1.90D, 2.16D};

    private static long lastBoostAppliedMs = Long.MIN_VALUE;

    public ElytraBoostFeature() {
        super("ElytraBoost", "Boosts elytra firework velocity beyond vanilla",
                FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static ElytraBoostFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(ElytraBoostFeature.class);
    }

    public static void markBoostApplied() {
        lastBoostAppliedMs = System.currentTimeMillis();
    }

    public static boolean isBoostActiveNow() {
        return System.currentTimeMillis() - lastBoostAppliedMs < BOOST_WINDOW_MS;
    }

    /**
     * Returns {x=boostXZ, y=boostY, z=boostXZ}. Yaw/pitch are the values that will be used
     * to build the rotation vector by the mixin — spoofed values from RotationContext when
     * an aura is controlling, real ones otherwise.
     */
    public Vec3 computeBoost(float yaw, float pitch) {
        float wrappedYaw = wrapDegrees(yaw);
        double xz;
        double y;

        switch (mode.getValue()) {
            case "Custom" -> {
                xz = customXZ(wrappedYaw);
                y  = customY(pitch);
                if (y > xz) xz = y;
            }
            case "Bravo" -> {
                xz = bravoXZ(pitch, wrappedYaw);
                y  = bravoY(pitch);
            }
            case "BravoGrief" -> {
                xz = arrayLookup(BRAVOGRIEF_XZ, fold(wrappedYaw));
                y  = arrayLookup(BRAVOGRIEF_Y,  fold(pitch));
            }
            default -> {
                xz = arrayLookup(REALLYWORLD_XZ, fold(wrappedYaw));
                y  = arrayLookup(REALLYWORLD_Y,  fold(pitch));
                if (y > xz) xz = y;
            }
        }

        return new Vec3(xz, y, xz);
    }

    private double customXZ(float yaw) {
        NumberSetting[] xz = {cXZ00, cXZ05, cXZ10, cXZ15, cXZ20, cXZ25, cXZ30, cXZ35, cXZ40};
        int i = (int) (fold(yaw) / 5.0F);
        if (i < 0) i = 0;
        if (i >= xz.length) i = xz.length - 1;
        return xz[i].getValue();
    }

    private double customY(float pitch) {
        NumberSetting[] y = {cY00, cY05, cY10, cY15, cY20, cY25, cY30, cY35, cY40};
        int i = (int) (fold(pitch) / 5.0F);
        if (i < 0) i = 0;
        if (i >= y.length) i = y.length - 1;
        return y[i].getValue();
    }

    /** method1289 — big yaw/pitch decision table for the Bravo mode. */
    private double bravoXZ(float pitch, float yaw) {
        float p = Math.abs(pitch);
        float y = Math.abs(wrapDegrees(yaw) % 90.0F);

        double v;
        if      (p >= 38.0F && p <= 52.0F) v = 2.00D;
        else if (p >= 32.0F && p <= 58.0F) v = 1.96D;
        else if (p >= 28.0F && p <= 62.0F) v = 1.95D;
        else if ((y >= 29.0F && y <= 61.0F) || (p >= 29.0F && p <= 61.0F)) v = 1.963D;
        else if ((y >= 28.0F && y <= 60.0F) || (p >= 28.0F && p <= 60.0F)) v = 1.954D;
        else if ((y >= 26.0F && y <= 64.0F) || (p >= 26.0F && p <= 64.0F)) v = 1.874D;
        else if ((y >= 24.0F && y <= 66.0F) || (p >= 24.0F && p <= 66.0F)) v = 1.750D;
        else if ((y >= 15.0F && y <= 75.0F) || (p >= 15.0F && p <= 75.0F)) v = 1.750D;
        else if ((y >= 13.0F && y <= 77.0F) || (p >= 13.0F && p <= 77.0F)) v = 1.750D;
        else if ((y >= 12.0F && y <= 78.0F) || (p >= 12.0F && p <= 78.0F)) v = 1.750D;
        else if ((y >=  8.0F && y <= 82.0F) || (p >= 11.0F && p <= 79.0F)) v = 1.750D;
        else if ((y >=  5.0F && y <= 85.0F) || (p >=  8.0F && p <= 82.0F)) v = 1.670D;
        else if (y <= 90.0F || p <= 90.0F) v = 1.670D;
        else v = 1.660D;

        return pitch > 15.0F ? v - 0.068D : v;
    }

    /** method1277 — pitch-only Y curve for the Bravo mode. */
    private double bravoY(float pitch) {
        float p = Math.abs(pitch);
        if (p >= 37.0F && p <= 38.0F) return 2.03D;
        if (p >= 25.0F && p <= 30.0F) return 2.00D;
        if (p >= 35.0F && p <= 45.0F) return 1.99D;
        if (p >= 40.0F && p <= 50.0F) return 1.97D;
        if (p >= 50.0F && p <= 60.0F) return 1.96D;
        if (p >= 51.0F && p <= 61.0F) return 1.85D;
        if (p >= 52.0F && p <= 65.0F) return 1.80D;
        return 1.59D;
    }

    private static double arrayLookup(double[] table, float folded) {
        int i = (int) (folded / 5.0F);
        if (i < 0) i = 0;
        if (i >= table.length) i = table.length - 1;
        return table[i];
    }

    /** Folds abs(angle) into [0, 45]. Matches Minced's method1283. */
    private static float fold(float angle) {
        float v = Math.abs(angle);
        if (v > 90.0F) v = 180.0F - v;
        if (v > 45.0F) v = 90.0F  - v;
        return v;
    }

    private static float wrapDegrees(float v) {
        float x = v % 360.0F;
        if (x >= 180.0F) x -= 360.0F;
        if (x <  -180.0F) x += 360.0F;
        return x;
    }
}
