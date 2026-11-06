package win.furryhook.feature.impl.visual;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class RtxFeature extends Feature {
    public static final String SOURCE_AUTO = "Auto";
    public static final String SOURCE_MANUAL = "Manual";

    public final BooleanSetting rays = register(new BooleanSetting(
            "Rays",
            true
    ).configKey("render.rtx.rays"));
    public final BooleanSetting reflections = register(new BooleanSetting(
            "Wet Floor",
            true
    ).configKey("render.rtx.reflections"));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced",
            false
    ).asExpander().configKey("render.rtx.advanced"));

    public final BooleanSetting moonRays = register(new BooleanSetting(
            "Moon Rays",
            true
    ).configKey("render.rtx.rays.moon").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final ColorSetting rayColor = register(new ColorSetting(
            "Ray Color",
            0xFFFFF0CC
    ).configKey("render.rtx.rays.color").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting rayTint = register(new NumberSetting(
            "Ray Tint",
            0.35D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.rtx.rays.tint").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting rayStrength = register(new NumberSetting(
            "Ray Strength",
            1.0D,
            0.0D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.rtx.rays.strength").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting rayDensity = register(new NumberSetting(
            "Ray Length",
            0.85D,
            0.2D,
            1.6D,
            0.05D,
            ""
    ).configKey("render.rtx.rays.density").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting rayDecay = register(new NumberSetting(
            "Ray Decay",
            0.96D,
            0.85D,
            0.995D,
            0.005D,
            ""
    ).configKey("render.rtx.rays.decay").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting raySpread = register(new NumberSetting(
            "Ray Spread",
            0.6D,
            0.2D,
            2.0D,
            0.05D,
            ""
    ).configKey("render.rtx.rays.spread").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting raySamples = register(new NumberSetting(
            "Ray Samples",
            40.0D,
            12.0D,
            64.0D,
            4.0D,
            ""
    ).configKey("render.rtx.rays.samples").visibleWhen(() -> this.rays.getValue()).advancedOnly());
    public final NumberSetting rayEmissive = register(new NumberSetting(
            "Emissive Rays",
            0.3D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.rtx.rays.emissive").visibleWhen(() -> this.rays.getValue()).advancedOnly());

    public final ModeSetting wetSource = register(new ModeSetting(
            "Wet Source",
            SOURCE_AUTO,
            SOURCE_AUTO,
            SOURCE_MANUAL
    ).configKey("render.rtx.reflections.source").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting wetness = register(new NumberSetting(
            "Wetness",
            0.75D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.rtx.reflections.wetness")
            .visibleWhen(() -> this.reflections.getValue() && SOURCE_MANUAL.equals(this.wetSource.getValue())).advancedOnly());
    public final NumberSetting puddles = register(new NumberSetting(
            "Puddles",
            0.55D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.rtx.reflections.puddles").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting puddleScale = register(new NumberSetting(
            "Puddle Size",
            3.5D,
            1.0D,
            12.0D,
            0.5D,
            " blocks"
    ).configKey("render.rtx.reflections.puddles.size").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting reflectStrength = register(new NumberSetting(
            "Reflect Strength",
            1.0D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.rtx.reflections.strength").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting fresnel = register(new NumberSetting(
            "Fresnel",
            1.0D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.rtx.reflections.fresnel").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting sharpness = register(new NumberSetting(
            "Sharpness",
            0.7D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.rtx.reflections.sharpness").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting darkening = register(new NumberSetting(
            "Darkening",
            0.35D,
            0.0D,
            0.8D,
            0.05D,
            ""
    ).configKey("render.rtx.reflections.darkening").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting surfaceAngle = register(new NumberSetting(
            "Surface Angle",
            0.86D,
            0.5D,
            0.98D,
            0.01D,
            ""
    ).configKey("render.rtx.reflections.angle").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final BooleanSetting reflectRipples = register(new BooleanSetting(
            "Ripples",
            true
    ).configKey("render.rtx.reflections.ripples").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting rippleAmount = register(new NumberSetting(
            "Ripple Amount",
            0.6D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.rtx.reflections.ripples.amount")
            .visibleWhen(() -> this.reflections.getValue() && this.reflectRipples.getValue()).advancedOnly());
    public final NumberSetting waveAmount = register(new NumberSetting(
            "Wave Amount",
            0.35D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.rtx.reflections.waves").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting reflectDistance = register(new NumberSetting(
            "Reflect Distance",
            28.0D,
            6.0D,
            96.0D,
            2.0D,
            " blocks"
    ).configKey("render.rtx.reflections.distance").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting reflectSteps = register(new NumberSetting(
            "Reflect Steps",
            32.0D,
            8.0D,
            64.0D,
            4.0D,
            ""
    ).configKey("render.rtx.reflections.steps").visibleWhen(() -> this.reflections.getValue()).advancedOnly());
    public final NumberSetting reflectThickness = register(new NumberSetting(
            "Thickness",
            0.7D,
            0.1D,
            4.0D,
            0.1D,
            " blocks"
    ).configKey("render.rtx.reflections.thickness").visibleWhen(() -> this.reflections.getValue()).advancedOnly());

    public RtxFeature() {
        super(
                "RTX",
                "Volumetric light shafts and wet mirror floors on one screen-space pass",
                FeatureCategory.VISUAL,
                BindSetting.UNBOUND
        );
    }

    public static RtxFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(RtxFeature.class);
    }

    public boolean usesRays() {
        return this.rays.getValue();
    }

    public boolean usesReflections() {
        return this.reflections.getValue();
    }

    public boolean usesManualWetness() {
        return SOURCE_MANUAL.equals(this.wetSource.getValue());
    }
}
