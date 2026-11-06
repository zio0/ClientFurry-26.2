package win.furryhook.feature.impl.visual;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

/**
 * Experimental alternate hand-fire trail, ported from a reference implementation
 * of the same idea (feedback slash-trail + camera reprojection). Kept separate
 * from {@link ShaderHandsFeature} so the two effects can be compared side by side.
 */
public final class HandsTestFeature extends Feature {
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.handstest.colorMode"));
    public final ColorSetting color = register(new ColorSetting(
            "Color",
            0x6633FF
    ).configKey("render.handstest.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));

    public final NumberSetting intensity = register(new NumberSetting(
            "Intensity", 0.85D, 0.1D, 1.5D, 0.01D, ""
    ).configKey("render.handstest.intensity"));
    public final NumberSetting speed = register(new NumberSetting(
            "Speed", 1.15D, 0.2D, 3.0D, 0.05D, ""
    ).configKey("render.handstest.speed"));
    public final NumberSetting length = register(new NumberSetting(
            "Length", 0.72D, 0.1D, 1.0D, 0.05D, ""
    ).configKey("render.handstest.length"));

    public final NumberSetting trailFade = register(new NumberSetting(
            "Trail Fade", 0.91D, 0.55D, 0.98D, 0.01D, ""
    ).configKey("render.handstest.trailFade"));
    public final NumberSetting trailSoftness = register(new NumberSetting(
            "Trail Softness", 1.35D, 0.45D, 2.5D, 0.05D, ""
    ).configKey("render.handstest.trailSoftness"));
    public final NumberSetting trailBlur = register(new NumberSetting(
            "Trail Blur", 1.55D, 0.2D, 3.0D, 0.05D, ""
    ).configKey("render.handstest.trailBlur"));
    public final NumberSetting handSoftness = register(new NumberSetting(
            "Hand Softness", 1.3D, 0.45D, 2.5D, 0.05D, ""
    ).configKey("render.handstest.handSoftness"));
    public final NumberSetting handBlur = register(new NumberSetting(
            "Hand Blur", 1.45D, 0.2D, 3.0D, 0.05D, ""
    ).configKey("render.handstest.handBlur"));
    public final NumberSetting smoke = register(new NumberSetting(
            "Smoke", 0.55D, 0.0D, 0.8D, 0.05D, ""
    ).configKey("render.handstest.smoke"));

    public HandsTestFeature() {
        super("HandsTest", "Experimental fire/smoke hand trail (test module)", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public int resolvedColor() {
        return ColorMode.resolve(this.colorMode, this.color);
    }
}
