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
 * "FuntimeDick" — the Expensive FunTime rotation processor rebuilt on this client's rotation
 * stack. Deliberately a separate mode from {@link FuntimeRotation}: the two share a name and
 * nothing else. Where Funtime is a minimum-jerk snap with a smooth tail, this one reproduces
 * the four behaviours the Expensive processor actually has:
 *
 * <ul>
 *   <li><b>Randomised aim vector</b> — Expensive feeds a per-tick random value into its
 *       trajectory solver instead of aiming at a fixed spot. Same here: a fresh point is drawn
 *       inside the hitbox, biased toward the upper torso, and a draw whose ray does not clip
 *       the target is pulled back toward the centre until it does. A point that cannot be
 *       reached never leaves this class.</li>
 *   <li><b>Distance-proportional closure</b> — the share of the gap closed per tick rises with
 *       the size of the gap and is jittered a few percent every tick, so the turn has neither a
 *       constant velocity nor a constant ratio for a pattern check to lock onto. Pitch runs a
 *       little behind yaw, the way a hand on a mouse does.</li>
 *   <li><b>Attack-window commitment</b> — the aura says when a hit is imminent
 *       ({@code attackLikely}). Expensive resolves its rotation several ticks ahead of the
 *       swing; the equivalent here is closing the remaining error outright on those ticks, so
 *       a swing never leaves on a rotation still mid-travel.</li>
 *   <li><b>Rotation hold</b> — Expensive schedules its rotation for twenty ticks, which keeps
 *       the head where it was for a beat after the target drops instead of whipping back on
 *       the same tick. {@link #tick} with a null target replays the last rotation sent for
 *       {@link #setHoldTicks(int)} ticks before letting go.</li>
 * </ul>
 *
 * Every step is quantised onto the vanilla mouse grid before it is sent, with the same
 * zero-promotion Funtime uses — without it a damped tail parks a fraction of a degree short of
 * the target and never closes it.
 */
public final class FuntimeDick implements AuraRotation {
    /** Error at which the closure share is already at its ceiling. */
    private static final float FULL_SPEED_ERROR = 55.0F;
    /** Per-tick safety caps. The curves stay inside these; they only catch teleports. */
    private static final float CAP_YAW = 46.0F;
    private static final float CAP_PITCH = 34.0F;
    /** Pitch trails yaw by this factor. */
    private static final float PITCH_LAG = 0.82F;
    /** Random scaling applied to the closure share every tick. */
    private static final float SPEED_JITTER = 0.09F;
    /** Draws taken before the fallback raycast answers. */
    private static final int POINT_DRAWS = 6;
    /** Bisection steps used to pull a missing draw back onto the silhouette. */
    private static final int POINT_BISECTIONS = 4;
    /** Ticks a drawn point is held before the next draw, so the aim is not pure noise. */
    private static final int POINT_HOLD_TICKS = 3;

    private double reach = 3.0D;
    private boolean throughWalls;
    private float minClosure = 0.30F;
    private float maxClosure = 0.86F;
    private float spread = 0.55F;
    private int holdTicks = 20;

    private int targetId = Integer.MIN_VALUE;
    private Vec3 point;
    private int pointTicks;
    private int holdLeft;
    private float heldYaw;
    private float heldPitch;
    private boolean hasHeld;

    /** Attack reach the drawn points are validated against; mirrors AuraFeature's setting. */
    public void setReach(double reach) {
        this.reach = reach;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    /** Share of the gap closed on the slowest tick (0..1) — the floor of the speed curve. */
    public void setMinClosure(float minClosure) {
        this.minClosure = Mth.clamp(minClosure, 0.05F, 1.0F);
    }

    /** Share of the gap closed on the fastest tick (0..1) — the ceiling of the speed curve. */
    public void setMaxClosure(float maxClosure) {
        this.maxClosure = Mth.clamp(maxClosure, 0.05F, 1.0F);
    }

    /** How far into the hitbox the draws reach: 0 is centre only, 1 the full silhouette. */
    public void setSpread(float spread) {
        this.spread = Mth.clamp(spread, 0.0F, 1.0F);
    }

    /** Ticks the last rotation is held after the target drops. Expensive uses twenty. */
    public void setHoldTicks(int holdTicks) {
        this.holdTicks = Math.max(0, holdTicks);
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            hold();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.pointTicks = 0;
            this.point = null;
        }

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 aimPoint = aimPoint(player, target, targetEyePos, eye);
        float[] aim = rotationTo(eye, aimPoint);

        float deltaYaw = Mth.wrapDegrees(aim[0] - currentYaw);
        float deltaPitch = Mth.clamp(aim[1], -89.0F, 89.0F) - currentPitch;
        float error = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        float closure = attackLikely ? 1.0F : closure(error);
        float stepYaw = Mth.clamp(deltaYaw * closure, -CAP_YAW, CAP_YAW);
        float stepPitch = Mth.clamp(deltaPitch * closure * PITCH_LAG, -CAP_PITCH, CAP_PITCH);

        double gcd = RotationContext.gcdStep();
        stepYaw = quantize(stepYaw, deltaYaw, gcd);
        stepPitch = quantize(stepPitch, deltaPitch, gcd);

        float yaw = currentYaw + stepYaw;
        float pitch = Mth.clamp(currentPitch + stepPitch, -89.0F, 89.0F);
        RotationContext.setRotation(yaw, pitch);

        this.heldYaw = yaw;
        this.heldPitch = pitch;
        this.hasHeld = true;
        this.holdLeft = this.holdTicks;
        this.pointTicks++;
    }

    /** True while the hold window is still replaying the last rotation after a target drop. */
    public boolean isHolding() {
        return this.hasHeld && this.holdLeft > 0;
    }

    @Override
    public void onAttack() {
        // A hit is where a real aim resettles: force the next draw.
        this.pointTicks = POINT_HOLD_TICKS;
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.point = null;
        this.pointTicks = 0;
        this.holdLeft = 0;
        this.hasHeld = false;
    }

    /**
     * Replays the last rotation sent while the hold window runs out, so the head stays put
     * instead of snapping back on the same tick the aura releases the target.
     */
    private void hold() {
        this.targetId = Integer.MIN_VALUE;
        this.point = null;
        this.pointTicks = 0;

        if (this.holdLeft <= 0 || !this.hasHeld) {
            this.hasHeld = false;
            return;
        }

        this.holdLeft--;
        RotationContext.setRotation(this.heldYaw, this.heldPitch);
        if (this.holdLeft <= 0) {
            this.hasHeld = false;
        }
    }

    /**
     * Closure share for this tick: rises with the size of the gap, so a long turn opens fast
     * and settles slow, then takes a few percent of noise so no two ticks share a ratio.
     */
    private float closure(float error) {
        float t = Mth.clamp(error / FULL_SPEED_ERROR, 0.0F, 1.0F);
        // Smoothstep, so the middle of the range is not a straight line either.
        float eased = t * t * (3.0F - 2.0F * t);
        float base = Mth.lerp(eased, this.minClosure, this.maxClosure);
        float jitter = 1.0F + (float) ThreadLocalRandom.current().nextDouble(-SPEED_JITTER, SPEED_JITTER);
        return Mth.clamp(base * jitter, 0.05F, 1.0F);
    }

    /**
     * The spot being aimed at. Held for a few ticks, then redrawn. Draws that do not clip the
     * hitbox are bisected back toward the centre — the silhouette is convex, so the reachable
     * fractions form one interval starting there — and the raycast's own answer is the
     * fallback when even the centre is unreachable.
     */
    private Vec3 aimPoint(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, Vec3 eye) {
        if (this.point != null && this.pointTicks < POINT_HOLD_TICKS && hitsPoint(player, target, eye, this.point)) {
            return this.point;
        }

        AABB box = target.getBoundingBox().deflate(0.05D);
        Vec3 centre = new Vec3(
                (box.minX + box.maxX) * 0.5D,
                Mth.clamp(box.maxY - 0.28D, box.minY + 0.1D, Math.max(box.minY + 0.1D, box.maxY - 0.05D)),
                (box.minZ + box.maxZ) * 0.5D
        );

        for (int draw = 0; draw < POINT_DRAWS; draw++) {
            Vec3 candidate = draw(box, centre);
            if (hitsPoint(player, target, eye, candidate)) {
                this.point = candidate;
                this.pointTicks = 0;
                return candidate;
            }

            Vec3 pulled = candidate;
            for (int step = 0; step < POINT_BISECTIONS; step++) {
                pulled = centre.add(pulled.subtract(centre).scale(0.5D));
                if (hitsPoint(player, target, eye, pulled)) {
                    this.point = pulled;
                    this.pointTicks = 0;
                    return pulled;
                }
            }
        }

        Vec3 fallback = AuraRaycast.findAimPoint(player, target, target.position(), this.reach, this.throughWalls, Vec3.ZERO);
        Vec3 resolved = fallback != null ? fallback : targetEyePos;
        this.point = resolved;
        this.pointTicks = 0;
        return resolved;
    }

    /** One draw: full width and depth scaled by spread, upper band of the box vertically. */
    private Vec3 draw(AABB box, Vec3 centre) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double halfX = Math.max(1.0E-4D, box.getXsize() * 0.5D * this.spread);
        double halfZ = Math.max(1.0E-4D, box.getZsize() * 0.5D * this.spread);
        double bandUp = Math.max(1.0E-4D, 0.10D * this.spread);
        double bandDown = Math.max(2.0E-4D, 0.55D * this.spread);

        return new Vec3(
                Mth.clamp(centre.x + random.nextDouble(-halfX, halfX), box.minX, box.maxX),
                Mth.clamp(centre.y + random.nextDouble(-bandDown, bandUp), box.minY, box.maxY),
                Mth.clamp(centre.z + random.nextDouble(-halfZ, halfZ), box.minZ, box.maxZ)
        );
    }

    private boolean hitsPoint(LocalPlayer player, LivingEntity target, Vec3 eye, Vec3 point) {
        float[] rotation = rotationTo(eye, point);
        return AuraRaycast.rotationIntersectsTarget(
                player,
                target,
                rotation[0],
                Mth.clamp(rotation[1], -89.0F, 89.0F),
                this.reach,
                0.0D,
                0.0D,
                this.throughWalls
        );
    }

    /**
     * Puts the step on the vanilla mouse grid. A step that rounds away to nothing while real
     * error is still open is promoted to a single grid unit, and no step is allowed to
     * overshoot the error it was built from.
     */
    private static float quantize(float step, float delta, double gcd) {
        if (gcd < 1.0E-4D) {
            return step;
        }
        if (Math.abs(step) > Math.abs(delta)) {
            step = delta;
        }

        float snapped = (float) (Math.round(step / gcd) * gcd);
        if (snapped == 0.0F && Math.abs(delta) >= gcd) {
            snapped = (float) (Math.signum(delta) * gcd);
        }
        return snapped;
    }

    private static float[] rotationTo(Vec3 eye, Vec3 point) {
        Vec3 diff = point.subtract(eye);
        double horizontal = Math.hypot(diff.x, diff.z);
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, horizontal)), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }
}
