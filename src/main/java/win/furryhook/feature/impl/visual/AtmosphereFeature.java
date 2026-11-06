package win.furryhook.feature.impl.visual;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ColorMode;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class AtmosphereFeature extends Feature {
    public static final String STORM_MANUAL = "Manual";
    public static final String STORM_WEATHER = "Weather";
    public static final String RAIN = "Rain";
    public static final String SNOW = "Snow";

    public final BooleanSetting fog = register(new BooleanSetting(
            "Fog",
            true
    ).configKey("render.atmosphere.fog"));
    public final BooleanSetting nightHaze = register(new BooleanSetting(
            "Night Haze",
            false
    ).configKey("render.atmosphere.haze"));
    public final BooleanSetting rain = register(new BooleanSetting(
            "Rain",
            true
    ).configKey("render.atmosphere.rain"));
    public final ModeSetting rainType = register(new ModeSetting(
            "Precipitation",
            RAIN,
            RAIN,
            SNOW
    ).configKey("render.atmosphere.rain.type").visibleWhen(() -> this.rain.getValue()));
    public final BooleanSetting lightning = register(new BooleanSetting(
            "Lightning",
            true
    ).configKey("render.atmosphere.lightning"));
    public final BooleanSetting advanced = register(new BooleanSetting(
            "Advanced",
            false
    ).asExpander().configKey("render.atmosphere.advanced"));

    public final ModeSetting fogColorMode = register(ColorMode.setting()
            .configKey("render.atmosphere.fog.colorMode")
            .visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final ColorSetting fogColor = register(new ColorSetting(
            "Fog Color",
            0xFF9AA5C8
    ).configKey("render.atmosphere.fog.color")
            .visibleWhen(() -> this.fog.getValue() && ColorMode.isCustom(this.fogColorMode)).advancedOnly());
    public final NumberSetting fogDensity = register(new NumberSetting(
            "Density",
            0.55D,
            0.05D,
            2.0D,
            0.01D,
            ""
    ).configKey("render.atmosphere.fog.density").visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final NumberSetting fogHeight = register(new NumberSetting(
            "Height",
            48.0D,
            8.0D,
            160.0D,
            1.0D,
            " blocks"
    ).configKey("render.atmosphere.fog.height").visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final NumberSetting ambientStrength = register(new NumberSetting(
            "Ambient",
            0.5D,
            0.0D,
            2.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.fog.ambient").visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final BooleanSetting syncWithSky = register(new BooleanSetting(
            "Sync With Sky",
            true
    ).configKey("render.atmosphere.fog.sync").visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final NumberSetting skySync = register(new NumberSetting(
            "Sync Amount",
            0.65D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.fog.sync.amount")
            .visibleWhen(() -> this.fog.getValue() && this.syncWithSky.getValue()).advancedOnly());
    public final NumberSetting sunsetBoost = register(new NumberSetting(
            "Sunset Boost",
            1.4D,
            0.0D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.fog.sync.sunset")
            .visibleWhen(() -> this.fog.getValue() && this.syncWithSky.getValue()).advancedOnly());
    public final NumberSetting turbulence = register(new NumberSetting(
            "Turbulence",
            0.35D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.fog.turbulence").visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final NumberSetting sunScatter = register(new NumberSetting(
            "Sun Scatter",
            0.8D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.fog.sunscatter").visibleWhen(() -> this.fog.getValue()).advancedOnly());
    public final BooleanSetting stormFog = register(new BooleanSetting(
            "Storm Fog",
            false
    ).configKey("render.atmosphere.fog.storm").visibleWhen(() -> this.fog.getValue()).advancedOnly());

    public final ModeSetting hazeColorMode = register(ColorMode.setting()
            .configKey("render.atmosphere.haze.colorMode")
            .visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final ColorSetting hazeColor = register(new ColorSetting(
            "Haze Color",
            0xFFB4C4E0
    ).configKey("render.atmosphere.haze.color")
            .visibleWhen(() -> this.nightHaze.getValue() && ColorMode.isCustom(this.hazeColorMode)).advancedOnly());
    public final NumberSetting hazeAmount = register(new NumberSetting(
            "Haze Amount",
            0.6D,
            0.05D,
            2.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.haze.amount").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final NumberSetting hazeThickness = register(new NumberSetting(
            "Haze Thickness",
            5.0D,
            1.0D,
            40.0D,
            0.5D,
            " blocks"
    ).configKey("render.atmosphere.haze.thickness").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final BooleanSetting hazeFollowGround = register(new BooleanSetting(
            "Follow Ground",
            true
    ).configKey("render.atmosphere.haze.follow").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final NumberSetting hazeOffset = register(new NumberSetting(
            "Haze Offset",
            1.5D,
            -8.0D,
            24.0D,
            0.5D,
            " blocks"
    ).configKey("render.atmosphere.haze.offset")
            .visibleWhen(() -> this.nightHaze.getValue() && this.hazeFollowGround.getValue()).advancedOnly());
    public final NumberSetting hazeLevel = register(new NumberSetting(
            "Haze Level",
            64.0D,
            -64.0D,
            320.0D,
            1.0D,
            " Y"
    ).configKey("render.atmosphere.haze.level")
            .visibleWhen(() -> this.nightHaze.getValue() && !this.hazeFollowGround.getValue()).advancedOnly());
    public final NumberSetting hazeBanks = register(new NumberSetting(
            "Haze Banks",
            0.6D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.haze.banks").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final NumberSetting hazeGlow = register(new NumberSetting(
            "Haze Glow",
            0.85D,
            0.0D,
            2.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.haze.glow").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final NumberSetting hazeMoonScatter = register(new NumberSetting(
            "Moon Scatter",
            1.0D,
            0.0D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.haze.moon").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());
    public final BooleanSetting hazeNightOnly = register(new BooleanSetting(
            "Night Only",
            true
    ).configKey("render.atmosphere.haze.nightonly").visibleWhen(() -> this.nightHaze.getValue()).advancedOnly());

    public final NumberSetting rainDensity = register(new NumberSetting(
            "Rain Density",
            1.0D,
            0.1D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.rain.density").visibleWhen(() -> this.rain.getValue()).advancedOnly());
    public final NumberSetting rainSpeed = register(new NumberSetting(
            "Rain Speed",
            1.0D,
            0.25D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.rain.speed").visibleWhen(() -> this.rain.getValue()).advancedOnly());
    public final ColorSetting rainColor = register(new ColorSetting(
            "Rain Color",
            0xFFC2D2E8
    ).configKey("render.atmosphere.rain.color").visibleWhen(() -> this.rain.getValue()).advancedOnly());
    public final NumberSetting rainOpacity = register(new NumberSetting(
            "Rain Opacity",
            0.55D,
            0.1D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.rain.opacity").visibleWhen(() -> this.rain.getValue()).advancedOnly());
    public final BooleanSetting ripples = register(new BooleanSetting(
            "Ripples",
            true
    ).configKey("render.atmosphere.rain.ripples")
            .visibleWhen(() -> this.rain.getValue() && !usesSnow()).advancedOnly());
    public final NumberSetting rippleStrength = register(new NumberSetting(
            "Ripple Strength",
            0.5D,
            0.1D,
            1.5D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.rain.ripples.strength")
            .visibleWhen(() -> this.rain.getValue() && !usesSnow() && this.ripples.getValue()).advancedOnly());

    public final ModeSetting stormSource = register(new ModeSetting(
            "Storm Source",
            STORM_MANUAL,
            STORM_MANUAL,
            STORM_WEATHER
    ).configKey("render.atmosphere.storm.source")
            .visibleWhen(() -> this.lightning.getValue() || (this.fog.getValue() && this.stormFog.getValue())).advancedOnly());
    public final NumberSetting stormPower = register(new NumberSetting(
            "Storm Power",
            0.5D,
            0.0D,
            1.0D,
            0.05D,
            ""
    ).configKey("render.atmosphere.storm.power")
            .visibleWhen(() -> STORM_MANUAL.equals(this.stormSource.getValue())
                    && (this.lightning.getValue() || (this.fog.getValue() && this.stormFog.getValue()))).advancedOnly());
    public final NumberSetting strikeRate = register(new NumberSetting(
            "Strike Rate",
            1.0D,
            0.25D,
            4.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.lightning.rate").visibleWhen(() -> this.lightning.getValue()).advancedOnly());
    public final NumberSetting strikeDistance = register(new NumberSetting(
            "Strike Distance",
            220.0D,
            60.0D,
            500.0D,
            10.0D,
            " blocks"
    ).configKey("render.atmosphere.lightning.distance").visibleWhen(() -> this.lightning.getValue()).advancedOnly());
    public final ColorSetting boltColor = register(new ColorSetting(
            "Bolt Color",
            0xFFCBDCFF
    ).configKey("render.atmosphere.lightning.color").visibleWhen(() -> this.lightning.getValue()).advancedOnly());
    public final NumberSetting boltWidth = register(new NumberSetting(
            "Bolt Width",
            1.6D,
            0.5D,
            5.0D,
            0.1D,
            " px"
    ).configKey("render.atmosphere.lightning.width").visibleWhen(() -> this.lightning.getValue()).advancedOnly());
    public final NumberSetting boltBranches = register(new NumberSetting(
            "Branches",
            3.0D,
            0.0D,
            4.0D,
            1.0D,
            ""
    ).configKey("render.atmosphere.lightning.branches").visibleWhen(() -> this.lightning.getValue()).advancedOnly());
    public final NumberSetting flashStrength = register(new NumberSetting(
            "Flash",
            1.0D,
            0.0D,
            2.0D,
            0.05D,
            "x"
    ).configKey("render.atmosphere.lightning.flash").visibleWhen(() -> this.lightning.getValue()).advancedOnly());

    public AtmosphereFeature() {
        super("Atmosphere", "Volumetric fog, rain and shader lightning on one storm driver", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public static AtmosphereFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(AtmosphereFeature.class);
    }

    public boolean usesFog() {
        return this.fog.getValue();
    }

    public boolean usesLightning() {
        return this.lightning.getValue();
    }

    public boolean usesRain() {
        return this.rain.getValue();
    }

    public boolean usesSnow() {
        return SNOW.equals(this.rainType.getValue());
    }

    public boolean usesNightHaze() {
        return this.nightHaze.getValue();
    }

    /**
     * Fog, haze, rain and lightning share one fullscreen pass, so any one of them
     * is enough to keep it alive - the layers that are off cost nothing, and with
     * fog off the pass writes the scene back untouched underneath the rest.
     */
    public boolean usesAtmosphere() {
        return usesFog() || usesLightning() || usesRain() || usesNightHaze();
    }
}
