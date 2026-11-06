package win.furryhook.feature.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.render.world.DynamicLightManager;

public final class FullBrightFeature extends Feature {
    public static final String LIGHT_SHADER = "Shader";
    public static final String LIGHT_ENGINE = "True Light";
    public static final String LIGHT_BOTH = "Both";

    public final BooleanSetting dynamic = register(new BooleanSetting(
            "Dynamic",
            true
    ).configKey("render.fullbright.dynamic"));
    public final NumberSetting brightness = register(new NumberSetting(
            "Brightness",
            0.5D,
            0.0D,
            10.0D,
            0.1D,
            ""
    ).configKey("render.fullbright.brightness").visibleWhen(() -> !this.dynamic.getValue()));
    public final NumberSetting minBrightness = register(new NumberSetting(
            "Min Brightness",
            0.0D,
            0.0D,
            10.0D,
            0.1D,
            ""
    ).configKey("render.fullbright.minBrightness").visibleWhen(() -> this.dynamic.getValue()));
    public final NumberSetting maxBrightness = register(new NumberSetting(
            "Max Brightness",
            10.0D,
            0.0D,
            10.0D,
            0.1D,
            ""
    ).configKey("render.fullbright.maxBrightness").visibleWhen(() -> this.dynamic.getValue()));
    public final ModeSetting lightsStyle = register(new ModeSetting(
            "Light Style",
            LIGHT_BOTH,
            LIGHT_SHADER,
            LIGHT_ENGINE,
            LIGHT_BOTH
    ).configKey("render.fullbright.lightsStyle"));
    public final NumberSetting lightIntensity = register(new NumberSetting(
            "Light Intensity",
            1.0D,
            0.0D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.fullbright.lightIntensity"));
    public final NumberSetting lightRadius = register(new NumberSetting(
            "Light Radius",
            1.0D,
            0.25D,
            3.0D,
            0.05D,
            "x"
    ).configKey("render.fullbright.lightRadius"));
    public final BooleanSetting lightOthers = register(new BooleanSetting(
            "Light From Others",
            true
    ).configKey("render.fullbright.lightOthers"));
    public final BooleanSetting lightItems = register(new BooleanSetting(
            "Light From Items",
            true
    ).configKey("render.fullbright.lightItems"));

    private double smoothedGamma = Double.NaN;

    public FullBrightFeature() {
        super("FullBright", "Adaptive brightness and dynamic item lights", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    public static FullBrightFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(FullBrightFeature.class);
    }

    public static double modifyGamma(double vanillaGamma) {
        FullBrightFeature feature = getEnabled();
        return feature == null || !Double.isFinite(feature.smoothedGamma)
                ? vanillaGamma
                : Math.max(vanillaGamma, feature.smoothedGamma);
    }

    public static boolean shouldSuppressDarkness() {
        return getEnabled() != null;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        Minecraft minecraft = event.getClient();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            this.smoothedGamma = Double.NaN;
            DynamicLightManager.INSTANCE.clear();
            return;
        }

        double target = this.dynamic.getValue()
                ? adaptiveGamma(level, player)
                : this.brightness.getValue();
        double vanilla = minecraft.options.gamma().get();
        target = Math.max(vanilla, target);
        if (!Double.isFinite(this.smoothedGamma)) {
            this.smoothedGamma = target;
        } else {
            this.smoothedGamma += (target - this.smoothedGamma) * 0.18D;
        }
        DynamicLightManager.INSTANCE.tick(minecraft, this);
    }

    @Override
    protected void onEnable() {
        this.smoothedGamma = Double.NaN;
        DynamicLightManager.INSTANCE.clear();
    }

    @Override
    protected void onDisable() {
        this.smoothedGamma = Double.NaN;
        DynamicLightManager.INSTANCE.clear();
    }

    @EventTarget
    public void onWorldLeave(WorldLeaveEvent event) {
        this.smoothedGamma = Double.NaN;
        DynamicLightManager.INSTANCE.clear();
    }

    public boolean usesShaderLights() {
        return this.lightsStyle.is(LIGHT_SHADER) || this.lightsStyle.is(LIGHT_BOTH);
    }

    public boolean usesEngineLights() {
        return this.lightsStyle.is(LIGHT_ENGINE) || this.lightsStyle.is(LIGHT_BOTH);
    }

    private double adaptiveGamma(ClientLevel level, LocalPlayer player) {
        float light = level.getMaxLocalRawBrightness(player.blockPosition()) / 15.0F;
        float darkness = 1.0F - Mth.clamp(light, 0.0F, 1.0F);
        float eased = darkness * darkness * (3.0F - 2.0F * darkness);
        double minimum = Math.min(this.minBrightness.getValue(), this.maxBrightness.getValue());
        double maximum = Math.max(this.minBrightness.getValue(), this.maxBrightness.getValue());
        return Mth.lerp(eased, minimum, maximum);
    }
}
