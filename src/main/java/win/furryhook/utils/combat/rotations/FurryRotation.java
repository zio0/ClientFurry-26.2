package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.utils.combat.AuraRaycast;

import java.util.concurrent.ThreadLocalRandom;

/**
 * "Furry" — a test rotation that bundles two deliberately loud behaviours, either of
 * which can be run on its own:
 *
 * <ul>
 *   <li><b>Spin 360</b> — the tick a hit lands the aim is thrown into a full 360° yaw
 *       sweep, spread over {@code spinTicks} ticks, then eased straight back onto the
 *       target. {@code AuraFeature} calls {@link #onAttack()} right after the attack
 *       packet and ticks the rotation afterwards, so the first spin step leaves in the
 *       same tick as the hit.</li>
 *   <li><b>Jitter</b> — every tick a fresh multi-point aim spot is drawn from anywhere
 *       inside the hitbox and then thrown off by as much jitter as the hitbox allows.
 *       The offset is not guessed: it starts at a deliberately oversized value and is
 *       bisected down to the largest one whose ray still clips the target, so the
 *       jitter is as wide as the silhouette permits and never wider.</li>
 * </ul>
 *
 * <p>Misses are ruled out on both sides: the jitter is raycast-validated here, and
 * {@code AuraFeature} withholds the attack while {@link #isSpinning()} and whenever the
 * spoofed rotation does not intersect the target.
 */
public final class FurryRotation implements AuraRotation {
    /** Yaw covered by one full sweep. */
    private static final float SPIN_DEGREES = 360.0F;
    /** Ticks spent easing back onto the target once the sweep closes. */
    private static final int RETURN_TICKS = 2;
    /** Per-tick cap while the aim is still travelling to a freshly picked target. */
    private static final float ACQUIRE_STEP = 70.0F;
    /** Oversized jitter handed to the search; the hitbox decides how much survives. */
    private static final float JITTER_YAW = 90.0F;
    private static final float JITTER_PITCH = 60.0F;
    /** Bisection steps used to find the largest jitter that still lands. */
    private static final int JITTER_STEPS = 6;
    /**
     * The surviving jitter sits right on the silhouette edge; back off a touch so the
     * GCD quantisation in {@link RotationContext#setRotation} cannot round it outside.
     */
    private static final float JITTER_SAFETY = 0.9F;

    private boolean spin = true;
    private boolean jitter = true;
    private int spinTicks = 6;
    private float jitterStrength = 1.0F;
    private double reach = 3.0D;
    private boolean throughWalls;

    private int targetId = Integer.MIN_VALUE;
    private float spinLeft;
    private float spinStep;
    private int spinSign = 1;
    private int returnTicks;

    /** Enables the post-hit 360; driven by AuraFeature's "Furry Mode" setting. */
    public void setSpin(boolean spin) {
        this.spin = spin;
        if (!spin) {
            cancelSpin();
        }
    }

    /** Enables the hitbox-wide jitter; driven by AuraFeature's "Furry Mode" setting. */
    public void setJitter(boolean jitter) {
        this.jitter = jitter;
    }

    /** Ticks the 360 is spread over — three is the floor, a bigger step would wrap. */
    public void setSpinTicks(int spinTicks) {
        this.spinTicks = Math.max(3, spinTicks);
    }

    /** Fraction of the hitbox-safe jitter actually used (0..1). */
    public void setJitterStrength(float jitterStrength) {
        this.jitterStrength = Mth.clamp(jitterStrength, 0.0F, 1.0F);
    }

    /** Attack reach the jitter is validated against; mirrors AuraFeature's setting. */
    public void setReach(double reach) {
        this.reach = reach;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    /** True while the 360 is still open, i.e. while the aim is off the target on purpose. */
    public boolean isSpinning() {
        return this.spinLeft > 0.0F;
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            cancelSpin();
        }

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        if (this.spinLeft > 0.0F) {
            spinStep(player, target, targetEyePos, currentYaw, currentPitch);
            return;
        }

        float[] aim = this.jitter
                ? jitteredAim(player, target, targetEyePos)
                : rotationTo(player.getEyePosition(1.0F), targetEyePos);

        float deltaYaw = Mth.wrapDegrees(aim[0] - currentYaw);
        float deltaPitch = aim[1] - currentPitch;

        // Coming out of a spin the target has moved, so close the leftover gap over a
        // couple of ticks instead of teleporting back onto it.
        if (this.returnTicks > 0) {
            this.returnTicks--;
            float ease = this.returnTicks == 0 ? 1.0F : 0.6F;
            deltaYaw *= ease;
            deltaPitch *= ease;
        } else {
            deltaYaw = Mth.clamp(deltaYaw, -ACQUIRE_STEP, ACQUIRE_STEP);
            deltaPitch = Mth.clamp(deltaPitch, -ACQUIRE_STEP, ACQUIRE_STEP);
        }

        RotationContext.setRotation(
                currentYaw + deltaYaw,
                Mth.clamp(currentPitch + deltaPitch, -90.0F, 90.0F)
        );
    }

    @Override
    public void onAttack() {
        if (!this.spin) {
            return;
        }
        this.spinLeft = SPIN_DEGREES;
        this.spinStep = SPIN_DEGREES / this.spinTicks;
        this.spinSign = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        this.returnTicks = 0;
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        cancelSpin();
    }

    private void cancelSpin() {
        this.spinLeft = 0.0F;
        this.spinStep = 0.0F;
        this.returnTicks = 0;
    }

    /**
     * One slice of the 360. Yaw walks a fixed step so the sweep is a real continuous
     * turn rather than a modulo wrap, while pitch is already drifting back toward the
     * target so the return only has yaw left to close.
     */
    private void spinStep(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, float currentYaw, float currentPitch) {
        float step = Math.min(this.spinLeft, this.spinStep);
        this.spinLeft -= step;

        float[] aim = rotationTo(player.getEyePosition(1.0F), targetEyePos);
        float pitch = Mth.lerp(0.4F, currentPitch, aim[1]);

        RotationContext.setRotation(
                currentYaw + step * this.spinSign,
                Mth.clamp(pitch, -90.0F, 90.0F)
        );

        if (this.spinLeft <= 0.0F) {
            this.spinLeft = 0.0F;
            this.returnTicks = RETURN_TICKS;
        }
    }

    /**
     * Picks a fresh point anywhere in the hitbox (multi-point, reach- and wall-checked by
     * {@link AuraRaycast#findAimPoint}) and throws the aim off it by the widest offset that
     * still clips the target. Falls back to plain tracking when nothing in the box is
     * reachable — out of range there is no jitter that could be called miss-free.
     */
    private float[] jitteredAim(LocalPlayer player, LivingEntity target, Vec3 targetEyePos) {
        Vec3 eye = player.getEyePosition(1.0F);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        AABB box = target.getBoundingBox();
        // findAimPoint scales the bias by 0.25 before clamping it into the box, so a span
        // of two box sizes is what it takes to reach every face from the centre.
        Vec3 bias = new Vec3(
                random.nextDouble(-2.0D, 2.0D) * box.getXsize(),
                random.nextDouble(-2.0D, 2.0D) * box.getYsize(),
                random.nextDouble(-2.0D, 2.0D) * box.getZsize()
        );

        Vec3 point = AuraRaycast.findAimPoint(player, target, target.position(), this.reach, this.throughWalls, bias);
        if (point == null) {
            return rotationTo(eye, targetEyePos);
        }

        float[] aim = rotationTo(eye, point);
        if (this.jitterStrength <= 0.0F) {
            return aim;
        }

        float offsetYaw = (float) random.nextDouble(-JITTER_YAW, JITTER_YAW) * this.jitterStrength;
        float offsetPitch = (float) random.nextDouble(-JITTER_PITCH, JITTER_PITCH) * this.jitterStrength;
        float scale = largestHittingScale(player, target, aim, offsetYaw, offsetPitch) * JITTER_SAFETY;

        return new float[]{
                aim[0] + offsetYaw * scale,
                Mth.clamp(aim[1] + offsetPitch * scale, -90.0F, 90.0F)
        };
    }

    /**
     * Largest fraction of the offered offset whose ray still intersects the target.
     * The aim point sits inside the hitbox and the silhouette is convex, so the safe
     * fractions form one interval starting at zero — a bisection finds its end, and the
     * returned value is always one that was verified to hit.
     */
    private float largestHittingScale(LocalPlayer player, LivingEntity target, float[] aim, float offsetYaw, float offsetPitch) {
        if (hits(player, target, aim, offsetYaw, offsetPitch, 1.0F)) {
            return 1.0F;
        }

        float low = 0.0F;
        float high = 1.0F;
        for (int step = 0; step < JITTER_STEPS; step++) {
            float mid = (low + high) * 0.5F;
            if (hits(player, target, aim, offsetYaw, offsetPitch, mid)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean hits(LocalPlayer player, LivingEntity target, float[] aim, float offsetYaw, float offsetPitch, float scale) {
        return AuraRaycast.rotationIntersectsTarget(
                player,
                target,
                aim[0] + offsetYaw * scale,
                Mth.clamp(aim[1] + offsetPitch * scale, -90.0F, 90.0F),
                this.reach,
                0.0D,
                0.0D,
                this.throughWalls
        );
    }

    private static float[] rotationTo(Vec3 eye, Vec3 point) {
        Vec3 diff = point.subtract(eye);
        double horizontal = Math.hypot(diff.x, diff.z);
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, horizontal)), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }
}
