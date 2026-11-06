package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

import java.util.concurrent.ThreadLocalRandom;

/**
 * "Advanced" rotation — modelled after the smooth tracking aura shown in the
 * reference gameplay clip (Energy client, {@code ares/ft} mode).
 *
 * <p>Behaviour: the aim eases toward the target with a distance-scaled speed so
 * far targets are acquired quickly and near targets settle smoothly, moving
 * targets are led slightly using their velocity, and the lock tightens almost to
 * a snap the tick an attack is about to land so hits stay consistent. A small
 * amount of tremor keeps the motion from looking perfectly linear.
 *
 * <p>This is a visual approximation of the clip, not a byte-for-byte copy of that
 * client's algorithm (its source/settings are not visible in the footage).
 */
public final class AdvancedRotation implements AuraRotation {
    // Fraction of the remaining angle covered per tick, by distance band.
    private static final float EASE_NEAR = 0.80F;
    private static final float EASE_FAR = 0.96F;
    private static final float NEAR_DISTANCE = 4.0F;
    private static final float FAR_DISTANCE = 40.0F;

    // Hard per-tick angular speed cap (degrees). Kept to human flick speeds so a 360 turn
    // sweeps over a few ticks instead of teleporting in one — a single-tick snap reads as an
    // aimbot rotation-speed flag. Hits still land meanwhile because Advanced attacks by
    // distance, not by raytrace.
    private static final float MAX_STEP_YAW = 42.0F;
    private static final float MAX_STEP_PITCH = 32.0F;

    // How far ahead of a moving target to lead, in seconds of its velocity.
    private static final double LEAD_SECONDS = 0.12D;

    private int targetId = Integer.MIN_VALUE;
    private float velYaw = 0.0F;
    private float velPitch = 0.0F;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.velYaw = 0.0F;
            this.velPitch = 0.0F;
        }

        // Lead moving targets slightly (the clip chases a running mob).
        Vec3 lead = target.getDeltaMovement().scale(LEAD_SECONDS * 20.0D);
        Vec3 aimPoint = targetEyePos.add(lead);

        Vec3 diff = aimPoint.subtract(player.getEyePosition(1.0F));
        double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        float rawYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float rawPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(diff.y, horizontal)), -90.0D, 90.0D);

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        float deltaYaw = Mth.wrapDegrees(rawYaw - currentYaw);
        float deltaPitch = rawPitch - currentPitch;
        float dist = (float) Math.hypot(deltaYaw, deltaPitch);

        // No instant snap on the hit tick: hits are distance-gated, so the aim never needs to
        // teleport onto the target. A capped, eased turn keeps rotation speed human-like and
        // avoids the aimbot-snap flags. The target is still hit while the aim sweeps toward it.

        // Distance-scaled easing: quick acquisition when far, tight settle when near.
        float t = Mth.clamp((dist - NEAR_DISTANCE) / (FAR_DISTANCE - NEAR_DISTANCE), 0.0F, 1.0F);
        float ease = Mth.lerp(t, EASE_NEAR, EASE_FAR);

        float stepYaw = deltaYaw * ease;
        float stepPitch = deltaPitch * ease;

        // Minimal damping — just enough to shave the hardest edge off a flick without
        // adding a perceptible lag tick.
        this.velYaw = this.velYaw * 0.12F + stepYaw * 0.88F;
        this.velPitch = this.velPitch * 0.12F + stepPitch * 0.88F;
        stepYaw = this.velYaw;
        stepPitch = this.velPitch;

        // Subtle humanising tremor, suppressed near the hit.
        if (!attackLikely && dist > 1.0F) {
            stepYaw += randomRange(-0.35F, 0.35F);
            stepPitch += randomRange(-0.18F, 0.18F);
        }

        stepYaw = Mth.clamp(stepYaw, -MAX_STEP_YAW, MAX_STEP_YAW);
        stepPitch = Mth.clamp(stepPitch, -MAX_STEP_PITCH, MAX_STEP_PITCH);

        RotationContext.setRotation(
                currentYaw + stepYaw,
                Mth.clamp(currentPitch + stepPitch, -90.0F, 90.0F)
        );
    }

    @Override
    public void onAttack() {
        // Bleed off accumulated speed so the aim doesn't sail past after a hit.
        this.velYaw *= 0.30F;
        this.velPitch *= 0.30F;
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.velYaw = 0.0F;
        this.velPitch = 0.0F;
    }

    private static float randomRange(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }
}
