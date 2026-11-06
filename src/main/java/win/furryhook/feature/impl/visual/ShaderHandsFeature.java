package win.furryhook.feature.impl.visual;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;

import java.util.List;

public final class ShaderHandsFeature extends Feature {
    public static final String FILL = "Fill";
    public static final String OUTLINE = "Outline";
    public static final String GLOW = "Glow";
    public static final String GLASS = "Glass";
    public static final String SOLID = "Solid";
    public static final String PLASMA = "Plasma";
    public static final String NEBULA = "Nebula";
    public static final String PRISM = "Prism";
    public static final String EMBERS = "Embers";
    public static final String FIRE = "Fire";
    public static final String ELECTRIC = "Electric";

    public final MultiSelectSetting effects = register(new MultiSelectSetting(
            "Effects",
            List.of(FILL, GLOW),
            FILL,
            OUTLINE,
            GLOW
    ).configKey("render.armtweaks.fill.effects"));
    public final ModeSetting colorMode = register(ColorMode.setting()
            .configKey("render.armtweaks.fill.colorMode"));
    public final ColorSetting color = register(new ColorSetting(
            "Color",
            0x7986CB
    ).configKey("render.armtweaks.fill.color")
            .visibleWhen(() -> ColorMode.isCustom(this.colorMode)));
    public final ModeSetting fillType = register(new ModeSetting(
            "Fill Type",
            GLASS,
            GLASS,
            SOLID,
            PLASMA,
            NEBULA,
            PRISM,
            EMBERS
    ).configKey("render.armtweaks.fill.type").visibleWhen(this::hasFill));
    public final BooleanSetting bothHands = register(new BooleanSetting(
            "Both Hands",
            true
    ).configKey("render.armtweaks.fill.bothHands"));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced",
            false
    ).asExpander().configKey("render.armtweaks.advanced"));

    public final NumberSetting fillOpacity = register(new NumberSetting(
            "Fill Opacity",
            0.19D,
            0.0D,
            1.0D,
            0.01D,
            ""
    ).configKey("render.armtweaks.fill.opacity").visibleWhen(() -> hasFill()).advancedOnly());
    public final NumberSetting plasmaSpeed = register(new NumberSetting(
            "Fill Speed",
            1.0D,
            0.1D,
            5.0D,
            0.1D,
            "x"
    ).configKey("render.armtweaks.fill.plasma.speed").visibleWhen(() -> hasAnimatedFill()).advancedOnly());
    public final NumberSetting glassBlur = register(new NumberSetting(
            "Glass Blur",
            19.0D,
            0.0D,
            60.0D,
            1.0D,
            "px"
    ).configKey("render.armtweaks.fill.glass.blur").visibleWhen(() -> hasGlass()).advancedOnly());
    public final BooleanSetting mirror = register(new BooleanSetting(
            "Mirror",
            true
    ).configKey("render.armtweaks.fill.glass.mirror").visibleWhen(() -> hasGlass()).advancedOnly());
    public final NumberSetting glowRadius = register(new NumberSetting(
            "Glow Radius",
            18.0D,
            1.0D,
            50.0D,
            1.0D,
            "px"
    ).configKey("render.armtweaks.fill.glow.radius").visibleWhen(() -> hasGlow()).advancedOnly());
    public final NumberSetting glowStrength = register(new NumberSetting(
            "Glow Strength",
            1.0D,
            0.0D,
            1.0D,
            0.01D,
            ""
    ).configKey("render.armtweaks.fill.glow.strength").visibleWhen(() -> hasGlow()).advancedOnly());
    public final BooleanSetting flame = register(new BooleanSetting(
            "Flame",
            true
    ).configKey("render.armtweaks.fill.glow.flame").visibleWhen(() -> hasGlow()).advancedOnly());
    public final ModeSetting flameStyle = register(new ModeSetting(
            "Flame Style",
            FIRE,
            FIRE,
            ELECTRIC
    ).configKey("render.armtweaks.fill.glow.flameStyle").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting flameSpeed = register(new NumberSetting(
            "Flame Speed",
            1.1D,
            0.1D,
            5.0D,
            0.1D,
            "x"
    ).configKey("render.armtweaks.fill.glow.flameSpeed").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting flameTrail = register(new NumberSetting(
            "Flame Trail",
            1.0D,
            0.0D,
            1.0D,
            0.01D,
            ""
    ).configKey("render.armtweaks.fill.glow.flameTrail").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting flameTurbulence = register(new NumberSetting(
            "Flame Turbulence",
            1.0D,
            0.0D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.armtweaks.fill.glow.flameTurbulence").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting flameChaos = register(new NumberSetting(
            "Flame Chaos",
            1.0D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.armtweaks.fill.glow.flameChaos").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting flameFlicker = register(new NumberSetting(
            "Flame Flicker",
            1.0D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.armtweaks.fill.glow.flameFlicker").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting flameBrightness = register(new NumberSetting(
            "Flame Brightness",
            1.0D,
            0.0D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.armtweaks.fill.glow.flameBrightness").visibleWhen(() -> hasFlame()).advancedOnly());
    public final NumberSetting outlineThickness = register(new NumberSetting(
            "Outline Thickness",
            1.5D,
            0.5D,
            5.0D,
            0.1D,
            "px"
    ).configKey("render.armtweaks.fill.outline.thickness").visibleWhen(() -> hasOutline()).advancedOnly());

    public ShaderHandsFeature() {
        super("ShaderHands", "Renders a colored silhouette over your first-person hands", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public int resolvedColor() {
        return ColorMode.resolve(this.colorMode, this.color);
    }

    public boolean hasFill() {
        return this.effects.isSelected(FILL);
    }

    public boolean hasGlass() {
        return hasFill() && this.fillType.is(GLASS);
    }

    public boolean hasPlasma() {
        return hasFill() && this.fillType.is(PLASMA);
    }

    public boolean hasAnimatedFill() {
        return hasFill() && !this.fillType.is(SOLID) && !this.fillType.is(GLASS);
    }

    public boolean hasOutline() {
        return this.effects.isSelected(OUTLINE);
    }

    public boolean hasGlow() {
        return this.effects.isSelected(GLOW);
    }

    public boolean hasFlame() {
        return hasGlow() && this.flame.getValue();
    }

    public boolean isElectric() {
        return hasFlame() && this.flameStyle.is(ELECTRIC);
    }
}
