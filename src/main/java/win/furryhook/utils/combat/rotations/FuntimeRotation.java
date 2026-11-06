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
 * "Funtime" — a smooth-snap tracker built around four behaviours:
 *
 * <ul>
 *   <li><b>Smooth snap</b> — acquiring a target (or losing it by more than
 *       {@link #SNAP_THRESHOLD}) opens a snap spread over a handful of ticks on a
 *       minimum-jerk curve, so the turn carries a bell-shaped velocity profile instead of a
 *       single instant jump. The curve is re-normalised against what is left to close every
 *       tick, which keeps it intact while the target keeps moving.</li>
 *   <li><b>GCD</b> — every step is quantised onto the vanilla mouse grid before it is sent,
 *       and a step that would round to zero while real error remains is promoted to one grid
 *       unit. Without that floor a heavily smoothed aim stalls just short of the target: the
 *       residual is smaller than the grid, rounds away, and never closes.</li>
 *   <li><b>Aim offset</b> — the aim rides slightly off the chosen point on a slow two-term
 *       wave rather than sitting nailed to it. The offset is not guessed: it is bisected down
 *       to the largest fraction whose ray still clips the hitbox, so it costs nothing in
 *       hits.</li>
 *   <li><b>Multipoint</b> — a small vertical band from head to upper torso with a slight
 *       lateral bias, rotated on hits and on a hold timer. Deliberately narrow: the point
 *       moves, the aim never wanders off the silhouette.</li>
 * </ul>
 */
public final class FuntimeRotation implements AuraRotation {
    /** Angular error that re-opens a snap once tracking has already settled. */
    private static final float SNAP_THRESHOLD = 18.0F;
    /** Per-tick safety caps; the curves stay well inside these, they only catch teleports. */
    private static final float SNAP_CAP_YAW = 60.0F;
    private static final float SNAP_CAP_PITCH = 40.0F;
    private static final float TRACK_CAP_YAW = 20.0F;
    private static final float TRACK_CAP_PITCH = 14.0F;
    /** Radians the offset wave advances per tick — slow enough to read as drift, not jitter. */
    private static final float OFFSET_PHASE_STEP = 0.16F;
    /** Bisection steps used to find the largest offset that still lands. */
    private static final int OFFSET_STEPS = 5;
    /**
     * The surviving offset sits right on the silhouette edge; back off a touch so the GCD
     * quantisation cannot round it outside.
     */
    private static final float OFFSET_SAFETY = 0.85F;
    /** Ticks a multipoint spot is held before the next one is taken. */
    private static final int POINT_HOLD_TICKS = 14;

    private double reach = 3.0D;
    private boolean throughWalls;
    private int snapTicks = 3;
    private float smoothness = 0.55F;
    private float offsetDegrees = 1.2F;
    private int multipoint = 3;

    private int targetId = Integer.MIN_VALUE;
    private int snapLeft;
    private int snapLength;
    private float snapProgress;
    private int pointIndex;
    private int pointTicks;
    private int pointSide = 1;
    private float offsetPhase;
    private float offsetSign = 1.0F;

    /** Attack reach the offset and multipoint are validated against; mirrors AuraFeature's setting. */
    public void setReach(double reach) {
        this.reach = reach;
    }

    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    /** Ticks one snap is spread over; two is the floor, below that the curve is a jump. */
    public void setSnapTicks(int snapTicks) {
        this.snapTicks = Math.max(2, snapTicks);
    }

    /** Tracking smoothness (0..1): 0 tracks hard, 1 trails the target on a long tail. */
    public void setSmoothness(float smoothness) {
        this.smoothness = Mth.clamp(smoothness, 0.0F, 1.0F);
    }

    /** Peak offset in degrees; the hitbox still decides how much of it survives. */
    public void setOffsetDegrees(float offsetDegrees) {
        this.offsetDegrees = Math.max(0.0F, offsetDegrees);
    }

    /** Number of spots in the head-to-torso band. */
    public void setMultipoint(int multipoint) {
        this.multipoint = Mth.clamp(multipoint, 1, 5);
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.pointTicks = 0;
            this.pointIndex = ThreadLocalRandom.current().nextInt(Math.max(1, this.multipoint));
            this.offsetPhase = (float) (ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0D);
            beginSnap();
        }

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 aimPoint = pickAimPoint(player, target, targetEyePos, eye);
        float[] aim = applyOffset(player, target, rotationTo(eye, aimPoint));

        float deltaYaw = Mth.wrapDegrees(aim[0] - currentYaw);
        float deltaPitch = aim[1] - currentPitch;
        float error = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        if (this.snapLeft <= 0 && error > SNAP_THRESHOLD) {
            beginSnap();
        }

        boolean snapping = this.snapLeft > 0;
        float fraction = snapping ? snapFraction() : trackFraction();
        float capYaw = snapping ? SNAP_CAP_YAW : TRACK_CAP_YAW;
        float capPitch = snapping ? SNAP_CAP_PITCH : TRACK_CAP_PITCH;

        float stepYaw = Mth.clamp(deltaYaw * fraction, -capYaw, capYaw);
        float stepPitch = Mth.clamp(deltaPitch * fraction, -capPitch, capPitch);

        double gcd = RotationContext.gcdStep();
        stepYaw = quantize(stepYaw, deltaYaw, gcd);
        stepPitch = quantize(stepPitch, deltaPitch, gcd);

        RotationContext.setRotation(
                currentYaw + stepYaw,
                Mth.clamp(currentPitch + stepPitch, -89.0F, 89.0F)
        );

        this.pointTicks++;
    }

    @Override
    public void onAttack() {
        // Hits are where a real player's aim resettles: take the next spot and flip the
        // offset over, so consecutive hits never land on the same pixel of the hitbox.
        advancePoint();
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.snapLeft = 0;
        this.snapLength = 0;
        this.snapProgress = 0.0F;
        this.pointIndex = 0;
        this.pointTicks = 0;
        this.pointSide = 1;
        this.offsetPhase = 0.0F;
        this.offsetSign = 1.0F;
    }

    private void beginSnap() {
        // One tick of slack on the length so two acquisitions in a row are never identical.
        this.snapLength = Math.max(2, this.snapTicks + (ThreadLocalRandom.current().nextBoolean() ? 0 : 1));
        this.snapLeft = this.snapLength;
        this.snapProgress = 0.0F;
    }

    /**
     * Share of the remaining gap to close this tick. The minimum-jerk curve gives the
     * absolute progress for the tick; dividing by what is still open turns it into a step
     * that stays on the curve even though the target — and with it the gap — keeps moving.
     */
    private float snapFraction() {
        int index = this.snapLength - this.snapLeft + 1;
        float done = (float) KinematicsUtil.getMinimumJerk(0.0D, 1.0D, (double) index / this.snapLength);
        float fraction = this.snapProgress >= 1.0F
                ? 1.0F
                : Mth.clamp((done - this.snapProgress) / (1.0F - this.snapProgress), 0.0F, 1.0F);

        this.snapProgress = done;
        this.snapLeft--;
        if (this.snapLeft <= 0) {
            this.snapProgress = 0.0F;
        }
        return fraction;
    }

    /** Settled tracking: a plain exponential tail whose length is the smoothness slider. */
    private float trackFraction() {
        return Mth.lerp(this.smoothness, 0.62F, 0.20F);
    }

    /**
     * Picks the spot to sit on. The held one is kept while it is still reachable and
     * unobstructed; otherwise the band is walked for the first one that is. Nothing in the
     * band reachable means the target is behind cover or out of range, and the caller's own
     * aim point is the honest answer.
     */
    private Vec3 pickAimPoint(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, Vec3 eye) {
        if (this.pointTicks >= POINT_HOLD_TICKS) {
            advancePoint();
        }

        int points = Math.max(1, this.multipoint);
        for (int offset = 0; offset < points; offset++) {
            int index = (this.pointIndex + offset) % points;
            Vec3 candidate = candidate(target, eye, index, points);
            float[] rotation = rotationTo(eye, candidate);
            if (hits(player, target, rotation[0], rotation[1])) {
                if (offset != 0) {
                    // The held spot went behind cover — keep the one that answered.
                    this.pointIndex = index;
                    this.pointTicks = 0;
                }
                return candidate;
            }
        }

        Vec3 fallback = AuraRaycast.findAimPoint(player, target, target.position(), this.reach, this.throughWalls, Vec3.ZERO);
        return fallback != null ? fallback : targetEyePos;
    }

    /**
     * One spot in the band: {@code index} walks head to upper torso, and the lateral bias
     * pushes it a fraction of the silhouette off centre, perpendicular to the line of sight.
     */
    private Vec3 candidate(LivingEntity target, Vec3 eye, int index, int points) {
        AABB box = target.getBoundingBox().deflate(0.05D);
        double top = box.maxY - 0.18D;
        double bottom = Mth.clamp(box.maxY - 0.75D, box.minY + 0.1D, top);
        double y = points == 1 ? top : Mth.lerp((double) index / (points - 1), top, bottom);

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;

        double dirX = centerX - eye.x;
        double dirZ = centerZ - eye.z;
        double length = Math.hypot(dirX, dirZ);
        if (length < 1.0E-6D) {
            return new Vec3(centerX, Mth.clamp(y, box.minY, box.maxY), centerZ);
        }

        // Perpendicular to the line of sight, so the bias is real lateral travel across the
        // silhouette rather than depth the target's own width would swallow.
        double perpX = -dirZ / length;
        double perpZ = dirX / length;
        double spread = Math.min(box.getXsize(), box.getZsize()) * 0.22D * this.pointSide;

        return new Vec3(
                Mth.clamp(centerX + perpX * spread, box.minX, box.maxX),
                Mth.clamp(y, box.minY, box.maxY),
                Mth.clamp(centerZ + perpZ * spread, box.minZ, box.maxZ)
        );
    }

    private void advancePoint() {
        int points = Math.max(1, this.multipoint);
        this.pointIndex = (this.pointIndex + 1) % points;
        this.pointTicks = 0;
        this.pointSide = -this.pointSide;
        this.offsetSign = -this.offsetSign;
    }

    /**
     * Rides the aim off the point on two waves of different periods, then scales the result
     * down to the largest fraction that still clips the target.
     */
    private float[] applyOffset(LocalPlayer player, LivingEntity target, float[] ideal) {
        if (this.offsetDegrees <= 0.0F) {
            return ideal;
        }

        this.offsetPhase += OFFSET_PHASE_STEP;

        float fast = (float) Math.sin(this.offsetPhase);
        float slow = (float) Math.sin(this.offsetPhase * 0.37F + 1.3F);
        float yawOffset = this.offsetDegrees * this.offsetSign * (fast * 0.75F + slow * 0.25F);
        float pitchOffset = this.offsetDegrees * 0.45F * (float) Math.sin(this.offsetPhase * 0.61F);

        float scale = largestHittingScale(player, target, ideal, yawOffset, pitchOffset) * OFFSET_SAFETY;
        return new float[]{
                ideal[0] + yawOffset * scale,
                Mth.clamp(ideal[1] + pitchOffset * scale, -89.0F, 89.0F)
        };
    }

    /**
     * Largest fraction of the offered offset whose ray still intersects the target. The aim
     * point sits inside the hitbox and the silhouette is convex, so the safe fractions form
     * one interval starting at zero — a bisection finds its end, and the returned value is
     * always one that was verified to hit.
     */
    private float largestHittingScale(LocalPlayer player, LivingEntity target, float[] ideal, float yawOffset, float pitchOffset) {
        if (offsetHits(player, target, ideal, yawOffset, pitchOffset, 1.0F)) {
            return 1.0F;
        }

        float low = 0.0F;
        float high = 1.0F;
        for (int step = 0; step < OFFSET_STEPS; step++) {
            float mid = (low + high) * 0.5F;
            if (offsetHits(player, target, ideal, yawOffset, pitchOffset, mid)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private boolean offsetHits(LocalPlayer player, LivingEntity target, float[] ideal, float yawOffset, float pitchOffset, float scale) {
        return hits(
                player,
                target,
                ideal[0] + yawOffset * scale,
                Mth.clamp(ideal[1] + pitchOffset * scale, -89.0F, 89.0F)
        );
    }

    private boolean hits(LocalPlayer player, LivingEntity target, float yaw, float pitch) {
        return AuraRaycast.rotationIntersectsTarget(
                player,
                target,
                yaw,
                pitch,
                this.reach,
                0.0D,
                0.0D,
                this.throughWalls
        );
    }

    /**
     * Puts the step on the vanilla mouse grid. A step that rounds away to nothing while real
     * error is still open is promoted to a single grid unit — otherwise a long smoothing tail
     * parks the aim just short of the target and never closes the last fraction of a degree.
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
