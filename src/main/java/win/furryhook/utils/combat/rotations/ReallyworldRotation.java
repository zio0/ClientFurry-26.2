package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

import java.util.concurrent.ThreadLocalRandom;

public final class ReallyworldRotation implements AuraRotation {
    private static final float LERP_SPEED = 0.45F;

    /** How fast the noise offset itself drifts toward a freshly rolled target each tick. */
    private static final float NOISE_LERP_SPEED = 0.15F;
    private static final float NOISE_YAW_RANGE = 0.6F;
    private static final float NOISE_PITCH_RANGE = 0.4F;

    private float smoothedYaw;
    private float smoothedPitch;
    private float noiseYaw;
    private float noisePitch;
    private boolean initialized;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            RotationContext.clear();
            return;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 delta = targetEyePos.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float idealYaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float idealPitch = Mth.clamp(
                (float) -Math.toDegrees(Math.atan2(delta.y, horizontal)),
                -90.0F,
                90.0F
        );

        if (!initialized) {
            this.smoothedYaw = RotationContext.isActive()
                    ? RotationContext.getServerYaw()
                    : player.getYRot();
            this.smoothedPitch = RotationContext.isActive()
                    ? RotationContext.getServerPitch()
                    : player.getXRot();
            this.initialized = true;
        }

        float deltaYaw = Mth.wrapDegrees(idealYaw - this.smoothedYaw);
        float deltaPitch = idealPitch - this.smoothedPitch;

        this.smoothedYaw = Mth.wrapDegrees(this.smoothedYaw + deltaYaw * LERP_SPEED);
        this.smoothedPitch = Mth.clamp(this.smoothedPitch + deltaPitch * LERP_SPEED, -90.0F, 90.0F);

        // Drift the noise offset toward a freshly rolled target each tick instead of
        // resampling it outright — an independent random value every tick reads as the
        // aim (and with it the player model, since apply() writes yBodyRot/yHeadRot too)
        // violently shaking rather than a subtle human-like wobble.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float targetNoiseYaw = (float) random.nextDouble(-NOISE_YAW_RANGE, NOISE_YAW_RANGE);
        float targetNoisePitch = (float) random.nextDouble(-NOISE_PITCH_RANGE, NOISE_PITCH_RANGE);
        this.noiseYaw += (targetNoiseYaw - this.noiseYaw) * NOISE_LERP_SPEED;
        this.noisePitch += (targetNoisePitch - this.noisePitch) * NOISE_LERP_SPEED;

        float finalYaw = this.smoothedYaw + this.noiseYaw;
        float finalPitch = Mth.clamp(this.smoothedPitch + this.noisePitch, -90.0F, 90.0F);

        RotationContext.setRotation(finalYaw, finalPitch);
    }

    @Override
    public void reset() {
        this.initialized = false;
        this.noiseYaw = 0.0F;
        this.noisePitch = 0.0F;
    }
}
