package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

import java.util.concurrent.ThreadLocalRandom;

/**
 * "Polar" — a slew-limited tracker: no snap phase at all, the aim walks toward the target
 * under a per-tick cap and the hit is held until what the server already has is inside the
 * tolerance cone.
 *
 * <ul>
 *   <li><b>Slew cap</b> — yaw and pitch each get a per-tick ceiling, re-rolled around its
 *       setting every tick, so no two turns of the same size take the same path.</li>
 *   <li><b>Ease-out</b> — the ceiling is scaled by how far there is left to go
 *       ({@code 0.35 + 0.65 * ease}), so the turn opens wide and closes small instead of
 *       running flat out into the target and stopping dead.</li>
 *   <li><b>Muscle overshoot</b> — inside the last few degrees, and only when the previous
 *       step was still a real move, a little noise is added on top of a step that was
 *       already capped at the remaining gap. That carries the aim slightly past the point
 *       and the next tick pulls it back, which is what a hand does.</li>
 *   <li><b>GCD</b> — the step is quantised onto the vanilla mouse grid, with a one-unit
 *       floor so a step that rounds to nothing while error remains still moves.</li>
 *   <li><b>Aim point</b> — upper torso at a fraction of hitbox height rather than dead
 *       centre, the fraction itself drawn from a narrow gaussian each tick.</li>
 * </ul>
 */
public final class PolarRotation implements AuraRotation {
    /** Spread the per-tick ceilings are re-rolled with. */
    private static final float YAW_STEP_SIGMA = 4.0F;
    private static final float PITCH_STEP_SIGMA = 3.0F;
    /** Angular gap at which the ease-out is fully open. */
    private static final float EASE_YAW_SPAN = 60.0F;
    private static final float EASE_PITCH_SPAN = 45.0F;
    /** Share of the ceiling still available at zero distance. */
    private static final float EASE_FLOOR = 0.35F;
    /** Gap under which the overshoot wobble is allowed. */
    private static final float OVERSHOOT_WINDOW = 6.0F;
    /** Previous step that has to have been a real move for the wobble to fire. */
    private static final float OVERSHOOT_MIN_STEP = 2.0F;
    private static final float OVERSHOOT_SIGMA = 0.6F;
    /** Spread on the aim height, as a fraction of hitbox height. */
    private static final float AIM_HEIGHT_SIGMA = 0.05F;

    private float maxYawStep = 35.0F;
    private float maxPitchStep = 22.0F;
    private float tolerance = 4.0F;
    private float aimHeight = 0.72F;

    private int targetId = Integer.MIN_VALUE;
    private float lastStepYaw;
    private float lastStepPitch;
    private float aimYaw;
    private float aimPitch;
    private boolean hasAim;

    /** Per-tick yaw ceiling before the ease-out and the re-roll. */
    public void setMaxYawStep(float maxYawStep) {
        this.maxYawStep = Math.max(1.0F, maxYawStep);
    }

    /** Per-tick pitch ceiling before the ease-out and the re-roll. */
    public void setMaxPitchStep(float maxPitchStep) {
        this.maxPitchStep = Math.max(1.0F, maxPitchStep);
    }

    /** Half-angle of the cone the sent rotation has to be inside before a hit is allowed. */
    public void setTolerance(float tolerance) {
        this.tolerance = Math.max(0.1F, tolerance);
    }

    /** Aim height as a fraction of the target's hitbox (0.72 is upper torso). */
    public void setAimHeight(float aimHeight) {
        this.aimHeight = Mth.clamp(aimHeight, 0.1F, 0.95F);
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.lastStepYaw = 0.0F;
            this.lastStepPitch = 0.0F;
        }

        float[] aim = aimAt(player, target, targetEyePos);
        this.aimYaw = aim[0];
        this.aimPitch = aim[1];
        this.hasAim = true;

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        float deltaYaw = Mth.wrapDegrees(aim[0] - currentYaw);
        float deltaPitch = aim[1] - currentPitch;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        float maxYaw = Math.max(1.0F, this.maxYawStep + (float) random.nextGaussian() * YAW_STEP_SIGMA);
        float maxPitch = Math.max(1.0F, this.maxPitchStep + (float) random.nextGaussian() * PITCH_STEP_SIGMA);

        float easeYaw = Math.min(1.0F, Math.abs(deltaYaw) / EASE_YAW_SPAN);
        float easePitch = Math.min(1.0F, Math.abs(deltaPitch) / EASE_PITCH_SPAN);

        float stepYaw = Math.copySign(
                Math.min(Math.abs(deltaYaw), maxYaw * (EASE_FLOOR + (1.0F - EASE_FLOOR) * easeYaw)),
                deltaYaw
        );
        float stepPitch = Math.copySign(
                Math.min(Math.abs(deltaPitch), maxPitch * (EASE_FLOOR + (1.0F - EASE_FLOOR) * easePitch)),
                deltaPitch
        );

        // Deliberately applied after the step was capped at the remaining gap, so it can carry
        // the aim past the point — the correction next tick is the half that reads as a hand.
        if (Math.abs(deltaYaw) < OVERSHOOT_WINDOW && Math.abs(this.lastStepYaw) > OVERSHOOT_MIN_STEP) {
            stepYaw += (float) random.nextGaussian() * OVERSHOOT_SIGMA;
        }

        double gcd = RotationContext.gcdStep();
        stepYaw = quantize(stepYaw, deltaYaw, gcd);
        stepPitch = quantize(stepPitch, deltaPitch, gcd);

        RotationContext.setRotation(
                currentYaw + stepYaw,
                Mth.clamp(currentPitch + stepPitch, -90.0F, 90.0F)
        );

        this.lastStepYaw = stepYaw;
        this.lastStepPitch = stepPitch;
    }

    /**
     * Whether the rotation the server already holds sits inside the tolerance cone around the
     * point last aimed at. False before the first tick on a target, so a hit can never leave
     * on a rotation that was never sent.
     */
    public boolean isOnTarget(LocalPlayer player) {
        if (!this.hasAim || player == null) {
            return false;
        }

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        return Math.abs(Mth.wrapDegrees(this.aimYaw - currentYaw)) <= this.tolerance
                && Math.abs(this.aimPitch - currentPitch) <= this.tolerance;
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.lastStepYaw = 0.0F;
        this.lastStepPitch = 0.0F;
        this.aimYaw = 0.0F;
        this.aimPitch = 0.0F;
        this.hasAim = false;
    }

    /**
     * Upper torso rather than hitbox centre, at a height re-drawn from a narrow gaussian every
     * tick. The horizontal component and the base height come from {@code targetEyePos}, so an
     * elytra lead point is followed instead of the target's real position when one is running.
     */
    private float[] aimAt(LocalPlayer player, LivingEntity target, Vec3 targetEyePos) {
        Vec3 eye = player.getEyePosition(1.0F);

        double height = target.getBbHeight();
        double fraction = Mth.clamp(
                this.aimHeight + ThreadLocalRandom.current().nextGaussian() * AIM_HEIGHT_SIGMA,
                0.05D,
                0.98D
        );
        double baseY = targetEyePos.y - target.getEyeHeight();

        double dx = targetEyePos.x - eye.x;
        double dy = (baseY + height * fraction) - eye.y;
        double dz = targetEyePos.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }

    /**
     * Puts the step on the vanilla mouse grid. Unlike the tracking rotations the step is not
     * clamped to the remaining gap first — the overshoot has to survive quantisation, that is
     * the whole point of it. A step that rounds away to nothing while error is still open is
     * promoted to one grid unit so a slow close never stalls below the grid.
     */
    private static float quantize(float step, float delta, double gcd) {
        if (gcd < 1.0E-4D) {
            return step;
        }

        float snapped = (float) (Math.round(step / gcd) * gcd);
        if (snapped == 0.0F && Math.abs(delta) >= gcd) {
            snapped = (float) (Math.signum(delta) * gcd);
        }
        return snapped;
    }
}
