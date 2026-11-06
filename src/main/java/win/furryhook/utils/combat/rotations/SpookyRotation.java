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
 * "Spooky" — a yaw-only, fast multi-point rotation. It closes the whole yaw gap quickly,
 * keeps every step on the mouse GCD grid, and spreads hits across an always-moving human
 * multi-point so the wire lacks the perfect-settle signature a neural anti-cheat hunts.
 *
 * <ul>
 *   <li><b>Yaw-only, fast</b> — pitch is never driven and the turn closes a large share of
 *       the gap each tick, so a hit is ready almost immediately after acquisition.</li>
 *   <li><b>Visible, noisy multipoint</b> — spots step through weighted zones (chest, head,
 *       shoulders, waist, hips) with a real lateral spread, spatial per-pick noise, and a
 *       short hold, so the aim visibly moves instead of parking on one pixel.</li>
 *   <li><b>GCD everywhere</b> — every step, including the micro-jitter, is quantised onto
 *       the vanilla mouse grid, and the strike compares against a stable per-spot target so
 *       hits flow the instant the aim is on the silhouette.</li>
 * </ul>
 */
public final class SpookyRotation implements AuraRotation {
    /** Band (degrees/tick) the turn speed is rolled from. */
    private static final float SPEED_MIN = 55.0F;
    private static final float SPEED_MAX = 120.0F;
    /** Amplitude (degrees) of the per-tick GCD-grid micro-jitter. */
    private static final float WIGGLE = 0.18F;
    /** Micro-noise (blocks) added to each freshly picked multi-point spot. */
    private static final double POINT_NOISE = 0.07D;
    /** Yaw error (degrees) below which a strike is considered "settled". */
    private static final float STRIKE_YAW_ERROR = 14.0F;
    /** Ticks a multi-point spot is held before the next one is taken. */
    private static final int POINT_HOLD_MIN = 2;
    private static final int POINT_HOLD_MAX = 4;
    /** Lateral spread of shoulder/hip spots, as a fraction of the silhouette. */
    private static final double LATERAL_SPREAD = 0.38D;

    private static double reach = 3.0D;
    private static boolean throughWalls = false;

    private int targetId = Integer.MIN_VALUE;
    private int pointIndex;
    private int pointTicks;
    private int pointHold;
    private int pointSide = 1;
    private float speedYaw;
    private float pointYaw;
    private boolean hasPoint;

    /** Attack reach the multipoint is validated against; mirrors AuraFeature's setting. */
    public void setReach(double reach) {
        SpookyRotation.reach = reach;
    }

    public void setThroughWalls(boolean throughWalls) {
        SpookyRotation.throughWalls = throughWalls;
    }

    /**
     * True when the rotation the server already holds really points into the target's hitbox,
     * tested with the player's own vertical aim so the yaw-only design can never be flagged.
     */
    public boolean isOnTarget(LocalPlayer player, LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        float serverYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float serverPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();
        return AuraRaycast.rotationIntersectsTarget(
                player, target, serverYaw, serverPitch, reach, 0.0D, 0.0D, throughWalls);
    }

    /**
     * Strike gate: server rotation on the hitbox and the yaw within a generous tolerance of the
     * <b>stable</b> per-spot target. The target is fixed when the spot is picked — it never
     * jumps between strikes, so hits flow the instant the aim arrives.
     */
    public boolean shouldStrike(LocalPlayer player, LivingEntity target) {
        if (!isOnTarget(player, target)) {
            return false;
        }
        if (!this.hasPoint) {
            return true;
        }
        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float yawError = Math.abs(Mth.wrapDegrees(this.pointYaw - currentYaw));
        return yawError <= STRIKE_YAW_ERROR;
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.pointIndex = rollZone(random);
            this.pointTicks = 0;
            this.pointHold = POINT_HOLD_MIN + random.nextInt(POINT_HOLD_MAX - POINT_HOLD_MIN + 1);
            this.speedYaw = SPEED_MIN + random.nextFloat() * (SPEED_MAX - SPEED_MIN);
            this.hasPoint = false;
        }

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        Vec3 eye = player.getEyePosition(1.0F);
        int zone = this.pointIndex % 7;
        Vec3 lateral = lateralPoint(target, eye, zone);
        float yawToPoint = yawTo(eye, lateral);

        // Every time the spot moves (or on first sight), pin the strike target so the gate
        // compares against something stable rather than a re-noised point each tick.
        if (this.pointTicks == 0 || !this.hasPoint) {
            this.pointYaw = yawToPoint;
            this.hasPoint = true;
        }

        float yawDelta = Mth.wrapDegrees(yawToPoint - currentYaw);

        // Fast non-stationary speed: re-roll each tick, hum around the last value.
        float drift = Mth.lerp(random.nextFloat() * 0.9F + 0.05F, this.speedYaw,
                SPEED_MIN + random.nextFloat() * (SPEED_MAX - SPEED_MIN));
        this.speedYaw = Mth.clamp(drift, SPEED_MIN * 0.7F, SPEED_MAX * 1.1F);

        double gcd = RotationContext.gcdStep();
        float step = Mth.clamp(yawDelta, -this.speedYaw, this.speedYaw);
        float microJitter = quantize(
                (float) ((random.nextDouble() - 0.5D) * 2.0D * WIGGLE),
                gcd);
        float queuedYaw = quantize(step + microJitter, gcd);

        // Yaw-only: pitch is handed straight back untouched.
        RotationContext.setRotation(currentYaw + queuedYaw, currentPitch);

        this.pointTicks++;
        if (this.pointTicks >= this.pointHold) {
            advancePoint(random);
        }
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.pointIndex = 0;
        this.pointTicks = 0;
        this.pointHold = 0;
        this.pointSide = 1;
        this.speedYaw = SPEED_MIN;
        this.hasPoint = false;
        this.pointYaw = 0.0F;
    }

    /**
     * Draws an aim zone the way a player actually aims: head and chest dominate, shoulders
     * next, then waist and hips — not an even spread, which is the synthetic signature.
     */
    private int rollZone(ThreadLocalRandom random) {
        int r = random.nextInt(100);
        if (r < 40) return 1;      // chest
        if (r < 70) return 0;      // head
        if (r < 86) return 3 + random.nextInt(2); // shoulders
        if (r < 96) return 2;      // waist
        return 5 + random.nextInt(2); // hips
    }

    /**
     * Horizontal spot on the chosen zone, pushed off-centre toward one side with a generous
     * lateral spread and a tiny per-pick micro-noise so spots visibly move between zones.
     */
    private Vec3 lateralPoint(LivingEntity target, Vec3 eye, int zone) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        AABB box = target.getBoundingBox().deflate(0.05D);
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;

        double side = (zone == 3 || zone == 5) ? this.pointSide : -this.pointSide;
        double dirX = centerX - eye.x;
        double dirZ = centerZ - eye.z;
        double length = Math.hypot(dirX, dirZ);
        if (length < 1.0E-6D) {
            return new Vec3(centerX + noise(random), zoneY(target, zone), centerZ + noise(random));
        }
        double perpX = -dirZ / length;
        double perpZ = dirX / length;
        double spread = Math.min(box.getXsize(), box.getZsize()) * LATERAL_SPREAD * side;
        return new Vec3(
                Mth.clamp(centerX + perpX * spread + noise(random), box.minX, box.maxX),
                zoneY(target, zone),
                Mth.clamp(centerZ + perpZ * spread + noise(random), box.minZ, box.maxZ)
        );
    }

    /** Tiny per-pick noise in blocks, so multi-point spots wander like a real aim. */
    private static double noise(ThreadLocalRandom random) {
        return (random.nextDouble() - 0.5D) * 2.0D * POINT_NOISE;
    }

    private double zoneY(LivingEntity target, int zone) {
        AABB box = target.getBoundingBox();
        double minY = box.minY;
        double maxY = box.maxY;
        double h = maxY - minY;
        return switch (zone) {
            case 0 -> maxY - h * 0.07D;            // head
            case 2 -> minY + h * 0.32D;            // waist
            case 3, 4 -> maxY - h * 0.24D;         // shoulders
            case 5, 6 -> minY + h * 0.44D;         // hips
            default -> maxY - h * 0.40D;           // chest
        };
    }

    private void advancePoint(ThreadLocalRandom random) {
        this.pointIndex = (this.pointIndex + 1) % 7;
        this.pointTicks = 0;
        this.pointSide = -this.pointSide;
        this.pointHold = POINT_HOLD_MIN + random.nextInt(POINT_HOLD_MAX - POINT_HOLD_MIN + 1);
    }

    /**
     * Puts the step on the vanilla mouse grid — the only deltas a real mouse can emit. A step
     * that rounds to zero while real error is still open is promoted to one grid unit so the
     * aim never parks a fraction of a degree short, and a promoted micro-step reads exactly
     * like the tiny tick of a human correction.
     */
    private static float quantize(float step, double gcd) {
        if (gcd < 1.0E-4D) {
            return step;
        }
        if (Math.abs(step) >= gcd) {
            return (float) (Math.round(step / gcd) * gcd);
        }
        return step == 0.0F ? 0.0F : (float) (Math.signum(step) * gcd);
    }

    private static float yawTo(Vec3 eye, Vec3 point) {
        Vec3 diff = point.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
    }
}