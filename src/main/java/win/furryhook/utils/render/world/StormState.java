package win.furryhook.utils.render.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import win.furryhook.feature.impl.visual.AtmosphereFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The one driver every atmosphere layer reads from, so fog, rain and lightning
 * all agree on how bad the weather currently is and which way the wind blows.
 * Strikes are stored in absolute world coordinates - they last long enough that
 * anchoring them to the camera would make them slide sideways while walking.
 */
public final class StormState {
    public static final StormState INSTANCE = new StormState();

    /** Seconds the drawn channel stays on screen. */
    private static final float BOLT_WINDOW = 0.45F;
    /** Seconds the sky keeps glowing after a strike. */
    private static final float FLASH_WINDOW = 0.95F;
    private static final float MAX_FRAME_DELTA = 0.1F;
    private static final int MAX_TRACKED = 4;

    /**
     * Scroll offsets are accumulated instead of derived from a clock so they can
     * be wrapped: feeding a raw elapsed time into the shader would quantize the
     * rain grid into visible steps once the float got big enough. The wraps are
     * multiples of every layer's cell height, so a wrap is seamless.
     */
    private static final float RAIN_WRAP = 4096.0F;
    private static final float DRIFT_WRAP = 65536.0F;
    /** Both ripple rates (1.0 and 0.6) turn this into a whole number of cycles. */
    private static final float RIPPLE_WRAP = 1000.0F;

    /** Blocks per second. Snow drifts down, rain is a fall streak. */
    private static final float RAIN_FALL = 24.0F;
    private static final float SNOW_FALL = 2.5F;

    private final Random random = new Random();
    private final List<Strike> strikes = new ArrayList<>();

    private long lastNanos;
    private ClientLevel lastLevel;
    private float intensity;
    private float windX = 1.0F;
    private float windZ;
    private float windStrength;
    private float windPhase;
    private float strikeCooldown = 0.8F;
    private float rainPhase;
    private float fogDrift;
    private float ripplePhase;
    private float skyVisibility = 1.0F;
    private float groundHeight = 64.0F;
    private boolean groundHeightKnown;

    private StormState() {
    }

    public void update(AtmosphereFeature feature, Vec3 cameraPos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != this.lastLevel) {
            this.lastLevel = level;
            reset();
        }

        long now = System.nanoTime();
        float delta = this.lastNanos == 0L ? 0.0F : (now - this.lastNanos) / 1.0E9F;
        this.lastNanos = now;
        // A pause, an alt-tab or a chunk-load hitch must not fast-forward the
        // storm through a dozen strikes on the frame the game comes back.
        delta = Mth.clamp(delta, 0.0F, MAX_FRAME_DELTA);

        advanceIntensity(feature, level, delta);
        advanceWind(delta);
        advanceScroll(feature, delta);
        advanceSkyVisibility(level, cameraPos, delta);
        advanceGroundHeight(level, cameraPos, delta);
        advanceStrikes(feature, cameraPos, delta);
    }

    public float rainPhase() {
        return this.rainPhase;
    }

    public float fogDrift() {
        return this.fogDrift;
    }

    public float ripplePhase() {
        return this.ripplePhase;
    }

    /** 0 under a roof, 1 out in the open, ramped so a doorway is not a hard cut. */
    public float skyVisibility() {
        return this.skyVisibility;
    }

    /**
     * Terrain surface height under the camera, smoothed. The night haze deck rides
     * on this so the mist lies on the ground in a valley and on the ground again on
     * the ridge above it, instead of the player having to dial an absolute Y in.
     */
    public float groundHeight() {
        return this.groundHeight;
    }

    public float intensity() {
        return this.intensity;
    }

    public float windX() {
        return this.windX;
    }

    public float windZ() {
        return this.windZ;
    }

    public float windStrength() {
        return this.windStrength;
    }

    /** The freshest channel still worth drawing, or null when nothing is lit. */
    public Strike activeStrike() {
        Strike best = null;
        for (Strike strike : this.strikes) {
            if (strike.age <= BOLT_WINDOW && (best == null || strike.age < best.age)) {
                best = strike;
            }
        }
        return best;
    }

    /**
     * Peak flash of every live *ambient* strike - overlapping strikes must not stack past
     * white. Manual strikes (Target ESP's "Молнии" mode) are excluded on purpose: a bolt
     * called down on a hit should read as a channel striking the target, not as the whole
     * screen flashing white on every swing.
     */
    public float flashStrength() {
        float strongest = 0.0F;
        for (Strike strike : this.strikes) {
            if (strike.manual) {
                continue;
            }
            strongest = Math.max(strongest, flashEnvelope(strike));
        }
        return strongest;
    }

    /** True while any tracked strike - ambient or manual - still has visible light left. */
    public boolean hasVisibleStrike() {
        for (Strike strike : this.strikes) {
            if (strike.age <= FLASH_WINDOW) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops a bolt straight down onto {@code impactPos}, independent of the ambient
     * weather driver - used by Target ESP's "Молнии" mode to call one down on a hit
     * landed, regardless of whether the Atmosphere module's own storm is running.
     */
    public void strikeAt(Vec3 impactPos, double distanceFromCamera) {
        if (impactPos == null) {
            return;
        }
        if (this.strikes.size() >= MAX_TRACKED) {
            this.strikes.remove(0);
        }

        Strike strike = new Strike();
        strike.seed = this.random.nextFloat() * 900.0F;
        strike.topX = impactPos.x();
        // Ambient strikes lean on 22-32 blocks because they're read from far away;
        // at melee range against a target only a few blocks off, a channel that
        // tall sits at a steep, near-grazing look angle far more often, which is
        // exactly where the screen-space projection gets numerically unstable.
        strike.topY = impactPos.y() + 9.0D + this.random.nextDouble() * 5.0D;
        strike.topZ = impactPos.z();
        strike.bottomX = impactPos.x();
        strike.bottomY = impactPos.y();
        strike.bottomZ = impactPos.z();
        strike.distance = distanceFromCamera;
        strike.falloff = (float) Mth.clamp(1.0D - distanceFromCamera / 600.0D, 0.35D, 1.0D);
        strike.manual = true;
        this.strikes.add(strike);
    }

    public void reset() {
        this.strikes.clear();
        this.lastNanos = 0L;
        this.strikeCooldown = 0.8F;
        this.intensity = 0.0F;
        this.rainPhase = 0.0F;
        this.fogDrift = 0.0F;
        this.ripplePhase = 0.0F;
        this.skyVisibility = 1.0F;
        this.groundHeightKnown = false;
    }

    private void advanceScroll(AtmosphereFeature feature, float delta) {
        float fall = (feature.usesSnow() ? SNOW_FALL : RAIN_FALL)
                * feature.rainSpeed.getValue().floatValue();
        this.rainPhase = (this.rainPhase + delta * fall) % RAIN_WRAP;
        this.fogDrift = (this.fogDrift + delta * (1.5F + 4.0F * this.windStrength)) % DRIFT_WRAP;
        this.ripplePhase = (this.ripplePhase + delta) % RIPPLE_WRAP;
    }

    private void advanceSkyVisibility(ClientLevel level, Vec3 cameraPos, float delta) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean open = level != null
                && cameraPos != null
                && level.dimensionType().hasSkyLight()
                && level.canSeeSky(BlockPos.containing(cameraPos))
                && (player == null || !player.isUnderWater());

        float target = open ? 1.0F : 0.0F;
        float step = delta * 2.5F;
        this.skyVisibility = Math.abs(target - this.skyVisibility) <= step
                ? target
                : this.skyVisibility + Math.signum(target - this.skyVisibility) * step;
    }

    /**
     * MOTION_BLOCKING_NO_LEAVES rather than MOTION_BLOCKING: mist settles on the
     * forest floor, not on the canopy, and a heightmap that jumped 12 blocks every
     * time the player walked under a tree would drag the whole deck with it.
     */
    private void advanceGroundHeight(ClientLevel level, Vec3 cameraPos, float delta) {
        if (level == null || cameraPos == null) {
            return;
        }
        BlockPos pos = BlockPos.containing(cameraPos);
        if (!level.hasChunkAt(pos)) {
            return;
        }

        float target = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (!this.groundHeightKnown) {
            this.groundHeight = target;
            this.groundHeightKnown = true;
            return;
        }

        // Proportional catch-up on top of a floor rate: strolling across gentle
        // terrain barely moves the deck, but stepping off a cliff does not leave
        // the mist hanging at the old height for ten seconds.
        float difference = target - this.groundHeight;
        float step = delta * (2.5F + Math.abs(difference) * 1.8F);
        this.groundHeight = Math.abs(difference) <= step
                ? target
                : this.groundHeight + Math.signum(difference) * step;
    }

    private void advanceIntensity(AtmosphereFeature feature, ClientLevel level, float delta) {
        float target;
        if (!feature.isEnabled()) {
            target = 0.0F;
        } else if (AtmosphereFeature.STORM_MANUAL.equals(feature.stormSource.getValue())) {
            target = feature.stormPower.getValue().floatValue();
        } else if (level != null) {
            // Thunder carries most of the weight: rain alone is a grey drizzle,
            // lightning belongs to the thunder half of vanilla's weather state.
            target = Mth.clamp(
                    level.getRainLevel(1.0F) * 0.45F + level.getThunderLevel(1.0F) * 0.85F,
                    0.0F,
                    1.0F
            );
        } else {
            target = 0.0F;
        }

        // ~2 s to cross the full range, so a storm rolls in instead of snapping on.
        float step = delta * 0.5F;
        this.intensity = Math.abs(target - this.intensity) <= step
                ? target
                : this.intensity + Math.signum(target - this.intensity) * step;
    }

    private void advanceWind(float delta) {
        this.windPhase += delta * 0.06F;
        float angle = this.windPhase + Mth.sin(this.windPhase * 0.73F) * 1.6F;
        this.windX = Mth.cos(angle);
        this.windZ = Mth.sin(angle);
        this.windStrength = 0.3F + 0.7F * this.intensity;
    }

    private void advanceStrikes(AtmosphereFeature feature, Vec3 cameraPos, float delta) {
        for (int i = this.strikes.size() - 1; i >= 0; i--) {
            Strike strike = this.strikes.get(i);
            strike.age += delta;
            if (strike.age > FLASH_WINDOW) {
                this.strikes.remove(i);
            }
        }

        if (!feature.isEnabled() || !feature.lightning.getValue() || this.intensity <= 0.02F || cameraPos == null) {
            return;
        }

        this.strikeCooldown -= delta;
        if (this.strikeCooldown > 0.0F) {
            return;
        }
        this.strikeCooldown = nextInterval(feature);
        if (this.strikes.size() >= MAX_TRACKED) {
            this.strikes.remove(0);
        }
        this.strikes.add(spawn(
                cameraPos,
                azimuth(),
                feature.strikeDistance.getValue() * (0.5D + this.random.nextDouble() * 0.5D)
        ));
    }

    private float nextInterval(AtmosphereFeature feature) {
        float rate = Math.max(feature.strikeRate.getValue().floatValue(), 0.05F);
        float base = Mth.lerp(this.intensity, 11.0F, 1.2F) / rate;
        return base * (0.55F + this.random.nextFloat() * 0.9F);
    }

    private Strike spawn(Vec3 cameraPos, double azimuth, double distance) {
        double baseX = cameraPos.x() + Math.cos(azimuth) * distance;
        double baseZ = cameraPos.z() + Math.sin(azimuth) * distance;
        // The channel hangs from cloud height down to below the camera, so its
        // tail is always cut off by terrain or the horizon instead of stopping
        // in mid-air where the seam would be obvious.
        double lean = (6.0D + this.random.nextDouble() * 16.0D) * this.windStrength;

        Strike strike = new Strike();
        strike.seed = this.random.nextFloat() * 900.0F;
        strike.topX = baseX + this.windX * lean;
        strike.topY = cameraPos.y() + 60.0D + this.random.nextDouble() * 70.0D;
        strike.topZ = baseZ + this.windZ * lean;
        strike.bottomX = baseX;
        strike.bottomY = cameraPos.y() - 20.0D - this.random.nextDouble() * 30.0D;
        strike.bottomZ = baseZ;
        strike.distance = distance;
        strike.falloff = (float) Mth.clamp(1.0D - distance / 600.0D, 0.35D, 1.0D);
        return strike;
    }

    /**
     * Two strikes in three land in front of the player. A uniform circle is the
     * honest distribution, but with a ~70 degree field of view it hides four out
     * of five channels behind you - the module would spend most of its time
     * flashing an empty sky, which is not what anyone turns it on for.
     */
    private double azimuth() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || this.random.nextFloat() > 0.66F) {
            return this.random.nextDouble() * Math.PI * 2.0D;
        }
        // Bearing the player is facing, in the same frame spawn() places strikes.
        double yaw = Math.toRadians(player.getYRot());
        double facing = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
        return facing + (this.random.nextDouble() - 0.5D) * 1.9D;
    }

    /**
     * Brightness of the drawn channel. The restrikes - the channel re-igniting a
     * few times before it dies - are what separate lightning from a plain fade.
     */
    public static float boltEnvelope(Strike strike) {
        float age = strike.age;
        if (age < 0.0F || age > BOLT_WINDOW) {
            return 0.0F;
        }
        float attack = Math.min(1.0F, age / 0.012F);
        float decay = (float) Math.exp(-age * 6.5F);
        return attack * decay * (0.42F + 0.58F * Math.abs(Mth.sin(age * 33.0F + strike.seed)));
    }

    private static float flashEnvelope(Strike strike) {
        float age = strike.age;
        if (age < 0.0F || age > FLASH_WINDOW) {
            return 0.0F;
        }
        float attack = Math.min(1.0F, age / 0.02F);
        float decay = (float) Math.exp(-age * 4.2F);
        return attack * decay * (0.6F + 0.4F * Mth.sin(age * 27.0F + strike.seed)) * strike.falloff;
    }

    public static final class Strike {
        public double topX;
        public double topY;
        public double topZ;
        public double bottomX;
        public double bottomY;
        public double bottomZ;
        public double distance;
        public float seed;
        /** Near strikes light the scene harder than ones on the horizon. */
        float falloff = 1.0F;
        /** Target ESP's "Молнии" mode: excluded from {@link #flashStrength()}. */
        boolean manual;

        float age;
    }
}
