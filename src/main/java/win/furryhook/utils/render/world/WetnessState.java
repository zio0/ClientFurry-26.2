package win.furryhook.utils.render.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.visual.AtmosphereFeature;
import win.furryhook.feature.impl.visual.RtxFeature;

/**
 * How wet the ground is, and how the water on it moves. Kept apart from
 * {@link StormState} because RTX has to work with the Atmosphere module off - but
 * it defers to StormState whenever that one is running, so a manually driven
 * storm makes puddles too instead of the two modules disagreeing about the weather.
 *
 * <p>Wetness is deliberately asymmetric: the ground soaks in seconds and dries in
 * half a minute. A surface that dried as fast as it wetted would read as a
 * lighting bug rather than as rain.
 */
public final class WetnessState {
    public static final WetnessState INSTANCE = new WetnessState();

    private static final float MAX_FRAME_DELTA = 0.1F;
    private static final float WET_RATE = 0.45F;
    private static final float DRY_RATE = 0.035F;
    /** Both ripple rates in the shader (1.0 and 0.6) divide this evenly. */
    private static final float PHASE_WRAP = 1000.0F;
    private static final float DRIFT_WRAP = 65536.0F;

    private long lastNanos;
    private ClientLevel lastLevel;
    private float wetness;
    private float rainAmount;
    private float ripplePhase;
    private float drift;

    private WetnessState() {
    }

    public void update(RtxFeature feature) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != this.lastLevel) {
            this.lastLevel = level;
            reset();
        }

        long now = System.nanoTime();
        float delta = this.lastNanos == 0L ? 0.0F : (now - this.lastNanos) / 1.0E9F;
        this.lastNanos = now;
        delta = Mth.clamp(delta, 0.0F, MAX_FRAME_DELTA);

        this.ripplePhase = (this.ripplePhase + delta) % PHASE_WRAP;
        this.drift = (this.drift + delta * 0.35F) % DRIFT_WRAP;

        float target;
        if (feature.usesManualWetness()) {
            target = feature.wetness.getValue().floatValue();
            this.rainAmount = target;
        } else {
            float weather = weatherIntensity(level);
            this.rainAmount = weather;
            target = weather;
        }

        float rate = target > this.wetness ? WET_RATE : DRY_RATE;
        float step = delta * rate;
        this.wetness = Math.abs(target - this.wetness) <= step
                ? target
                : this.wetness + Math.signum(target - this.wetness) * step;
    }

    /** 0 bone dry, 1 soaked. Lags the weather in both directions. */
    public float wetness() {
        return this.wetness;
    }

    /** How hard it is coming down right now - drives how many rings are live. */
    public float rainAmount() {
        return this.rainAmount;
    }

    public float ripplePhase() {
        return this.ripplePhase;
    }

    public float drift() {
        return this.drift;
    }

    public void reset() {
        this.lastNanos = 0L;
        this.wetness = 0.0F;
        this.rainAmount = 0.0F;
        this.ripplePhase = 0.0F;
        this.drift = 0.0F;
    }

    /**
     * Atmosphere's own driver wins when it is running: it already folds in the
     * module's Manual storm power and its smoothed ramp, so reading vanilla's rain
     * level instead would have RTX's puddles and Atmosphere's rain disagreeing
     * about whether there is a storm.
     */
    private static float weatherIntensity(ClientLevel level) {
        AtmosphereFeature atmosphere = FeatureManager.INSTANCE.getFeature(AtmosphereFeature.class);
        if (atmosphere != null && atmosphere.isEnabled() && atmosphere.usesRain()) {
            return Mth.clamp(StormState.INSTANCE.intensity(), 0.0F, 1.0F);
        }
        if (level == null) {
            return 0.0F;
        }
        return Mth.clamp(level.getRainLevel(1.0F), 0.0F, 1.0F);
    }
}
