package win.furryhook.utils.combat.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Test rotation — universal multi-point anticheat bypass. Designed to slip past the rotation
 * and reach checks that Grim, Vulcan, Verus, Matrix, Polar, Intave, NCP, AAC, Karhu, Spartan
 * and the standard bukkit/paper AC set actually run.
 *
 * <p>Key idea: this rotation <b>never quantises yaw/pitch itself</b> — {@link RotationContext}
 * does GCD quantisation with the real user sensitivity ({@code f = sens*0.6+0.2; step = f³·1.2}).
 * Double-quantising with a hardcoded sensitivity was the previous version's bug: the final
 * server-side angle drifted off the hitbox, so reach checks rejected the swing and damage got
 * cut. Here every computed angle is handed to the context raw and only quantised once.
 *
 * <p>Strategy per tick:
 * <ol>
 *   <li>Sample up to 13 candidate points across the target hitbox (throat, chest, sternum,
 *       waist, 4 shoulder corners, 4 hip corners, closest point on box surface to eye).</li>
 *   <li>For each candidate, compute the yaw/pitch that hits it, then simulate the ray at that
 *       angle and check it actually intersects the target hitbox first (before any wall).</li>
 *   <li>Score = wall penalty (if the eye→point line is blocked) + angular delta from current
 *       smoothed rotation. Smallest total wins.</li>
 *   <li>Ease toward the winner with a per-tick cap under the human-tracking ceiling, add a
 *       two-sine tremor so the packet curve isn't perfectly smooth, and optional overshoot on
 *       sharp turns.</li>
 *   <li>When {@link #tick(LocalPlayer, LivingEntity, Vec3, boolean) attackLikely} is set, the
 *       tremor and idle jitter are muted and the point re-picked to guarantee a hitbox
 *       raytrace at the outgoing angle — every swing lands on a rotation the server also sees
 *       as pointing into the target, which is what makes reach checks pass and damage stick.</li>
 *   <li>Between actions, sub-degree idle drift keeps the packet stream from freezing on a
 *       constant angle, which some AC heuristics flag as "aura frozen while swinging."</li>
 * </ol>
 *
 * <p>Pitch is clamped to ±89.5° so the impossible ±90° a real player can't produce never
 * appears in a packet. Yaw wrap is handled by {@link Mth#wrapDegrees}. Acquisition delay of
 * 2-4 ticks smooths the first turn-in on a new target.
 */
public final class TestRotation implements AuraRotation {

    /** Per-tick turn cap — well under the human-tracking / AC-invalid-speed ceiling. */
    private static final float MAX_YAW_STEP = 35.0F;
    private static final float MAX_PITCH_STEP = 20.0F;

    /**
     * Adaptive ease profile — matches a real player's tracking motor. Large angular error
     * triggers a fast saccade; small error uses a slow steady hold. An aimbot's constant ease
     * is the opposite of natural tracking and one of the easiest visual tells from outside.
     */
    private static final float EASE_SACCADE = 0.68F;
    private static final float EASE_TRACK = 0.32F;
    private static final float EASE_HOLD = 0.18F;

    /** Yaw delta (deg) thresholds separating the three ease bands. */
    private static final float SACCADE_THRESHOLD = 6.0F;
    private static final float HOLD_THRESHOLD = 1.4F;

    /**
     * Tremor amplitude peaks during a hold (real hand micro-corrections) and drops toward zero
     * during a saccade (motor path is committed). Aimbots do it the other way round.
     */
    private static final float TREMOR_YAW_HOLD = 0.14F;
    private static final float TREMOR_YAW_TRACK = 0.06F;
    private static final float TREMOR_PITCH_HOLD = 0.10F;
    private static final float TREMOR_PITCH_TRACK = 0.04F;

    /** Idle micro-drift amplitude when the aim is fully settled. */
    private static final float IDLE_JITTER = 0.03F;

    /** Blocked-raycast penalty (degrees-equivalent) in the candidate score. */
    private static final float BLOCKED_PENALTY = 25.0F;

    /** How far along the sight line to check for the actual hitbox intersection. */
    private static final double RAY_DISTANCE = 6.0D;

    /**
     * How many ticks ahead of the current target position to sample candidate points, so the
     * outgoing packet points where the target will be when the server processes it — kills the
     * "aim always one tick behind a moving target" miss pattern.
     */
    private static final double LEAD_TICKS = 1.0D;

    private float smoothedYaw;
    private float smoothedPitch;
    private boolean initialized;

    private int acquireDelay;
    private LivingEntity lastTarget;

    private float lockedYaw;
    private float lockedPitch;
    private boolean hasLock;

    private float tremorPhase;
    private float idlePhase;
    private int overshootCooldown;
    private float overshootBias;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            RotationContext.clear();
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (target != lastTarget) {
            lastTarget = target;
            acquireDelay = 2 + random.nextInt(3);
            overshootCooldown = 0;
            overshootBias = 0.0F;
            hasLock = false;
        }

        if (!initialized) {
            smoothedYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
            smoothedPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();
            initialized = true;
        }

        selectBestPoint(player, target, attackLikely);

        if (acquireDelay > 0) {
            acquireDelay--;
            RotationContext.setRotation(smoothedYaw, clampPitch(smoothedPitch));
            return;
        }

        float deltaYaw = Mth.wrapDegrees(lockedYaw - smoothedYaw);
        float deltaPitch = lockedPitch - smoothedPitch;
        float absDelta = Math.abs(deltaYaw) + Math.abs(deltaPitch) * 0.5F;

        float ease = absDelta > SACCADE_THRESHOLD ? EASE_SACCADE
                : absDelta > HOLD_THRESHOLD ? EASE_TRACK
                : EASE_HOLD;

        float stepYaw = Mth.clamp(deltaYaw * ease, -MAX_YAW_STEP, MAX_YAW_STEP);
        float stepPitch = Mth.clamp(deltaPitch * ease, -MAX_PITCH_STEP, MAX_PITCH_STEP);

        if (!attackLikely && overshootCooldown <= 0 && Math.abs(deltaYaw) > 12.0F && random.nextFloat() < 0.15F) {
            overshootBias = Math.signum(deltaYaw) * (0.6F + random.nextFloat() * 1.0F);
            overshootCooldown = 12 + random.nextInt(20);
        } else if (overshootCooldown > 0) {
            overshootCooldown--;
            overshootBias *= 0.7F;
        } else {
            overshootBias = 0.0F;
        }

        smoothedYaw = Mth.wrapDegrees(smoothedYaw + stepYaw + overshootBias);
        smoothedPitch = clampPitch(smoothedPitch + stepPitch);

        float finalYaw = smoothedYaw;
        float finalPitch = smoothedPitch;

        if (!attackLikely) {
            tremorPhase += 0.37F;
            idlePhase += 0.11F;

            float tremorScale = absDelta > SACCADE_THRESHOLD ? 0.0F
                    : absDelta > HOLD_THRESHOLD ? 1.0F
                    : 1.3F;
            float tremorYawAmp = (absDelta > HOLD_THRESHOLD ? TREMOR_YAW_TRACK : TREMOR_YAW_HOLD) * tremorScale;
            float tremorPitchAmp = (absDelta > HOLD_THRESHOLD ? TREMOR_PITCH_TRACK : TREMOR_PITCH_HOLD) * tremorScale;

            float tremorY = (float) (Math.sin(tremorPhase) * 0.6D + Math.sin(tremorPhase * 1.7D + 0.9D) * 0.4D) * tremorYawAmp;
            float tremorP = (float) (Math.sin(tremorPhase * 1.3D + 0.4D) * 0.6D + Math.sin(tremorPhase * 0.8D) * 0.4D) * tremorPitchAmp;
            float idleY = absDelta < HOLD_THRESHOLD ? (float) Math.sin(idlePhase) * IDLE_JITTER : 0.0F;

            finalYaw += tremorY + idleY;
            finalPitch += tremorP;
        }

        RotationContext.setRotation(finalYaw, clampPitch(finalPitch));
    }

    /**
     * Score candidate points across the hitbox. When {@code attackLikely} is set, only accept a
     * candidate whose ray at the resulting yaw/pitch actually intersects the target hitbox — if
     * none pass, fall back to the closest-in-angle so we still track. Between attacks the
     * angular delta alone drives selection, with a wall penalty on line of sight.
     */
    private void selectBestPoint(LocalPlayer player, LivingEntity target, boolean attackLikely) {
        Vec3 eye = player.getEyePosition();
        Vec3 vel = target.getDeltaMovement();
        double leadX = vel.x * LEAD_TICKS;
        double leadY = vel.y * LEAD_TICKS;
        double leadZ = vel.z * LEAD_TICKS;
        AABB box = target.getBoundingBox().move(leadX, leadY, leadZ);
        double w = box.getXsize();
        double h = box.getYsize();
        double d = box.getZsize();
        double cx = (box.minX + box.maxX) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;
        double eyeY = box.minY + target.getEyeHeight();
        double chestY = box.minY + h * 0.72D;
        double sternumY = box.minY + h * 0.60D;
        double waistY = box.minY + h * 0.45D;

        double sx = w * 0.28D;
        double sz = d * 0.28D;

        Vec3[] candidates = new Vec3[]{
                new Vec3(cx, sternumY, cz),
                new Vec3(cx, chestY, cz),
                new Vec3(cx, waistY, cz),
                new Vec3(cx, eyeY - 0.08D, cz),
                new Vec3(cx + sx, chestY, cz + sz),
                new Vec3(cx - sx, chestY, cz + sz),
                new Vec3(cx + sx, chestY, cz - sz),
                new Vec3(cx - sx, chestY, cz - sz),
                new Vec3(cx + sx, waistY, cz),
                new Vec3(cx - sx, waistY, cz),
                new Vec3(cx, waistY, cz + sz),
                new Vec3(cx, waistY, cz - sz),
                closestPointOnBox(eye, box)
        };

        float bestScore = Float.POSITIVE_INFINITY;
        float bestYaw = lockedYaw;
        float bestPitch = lockedPitch;
        boolean bestHitsBox = false;
        boolean any = false;

        for (Vec3 point : candidates) {
            double dx = point.x - eye.x;
            double dy = point.y - eye.y;
            double dz = point.z - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);

            float pointYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
            float pointPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(dy, horiz)), -89.5F, 89.5F);

            float yawDelta = Math.abs(Mth.wrapDegrees(pointYaw - smoothedYaw));
            float pitchDelta = Math.abs(pointPitch - smoothedPitch);

            boolean hitsBox = rayHitsBox(eye, pointYaw, pointPitch, box);
            boolean lineClear = isVisible(player, eye, point);

            float score = yawDelta + pitchDelta * 1.2F;
            if (!lineClear) score += BLOCKED_PENALTY;
            if (attackLikely && !hitsBox) score += BLOCKED_PENALTY * 2.0F;

            boolean better = attackLikely
                    ? (hitsBox && !bestHitsBox) || (hitsBox == bestHitsBox && score < bestScore)
                    : score < bestScore;

            if (better) {
                bestScore = score;
                bestYaw = pointYaw;
                bestPitch = pointPitch;
                bestHitsBox = hitsBox;
                any = true;
            }
        }

        if (any) {
            lockedYaw = bestYaw;
            lockedPitch = bestPitch;
            hasLock = true;
        }
    }

    /** Simulate the sight line at the given yaw/pitch and check it intersects the hitbox. */
    private static boolean rayHitsBox(Vec3 eye, float yaw, float pitch, AABB box) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        double dx = -Math.sin(yawRad) * cosPitch;
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * cosPitch;
        Vec3 end = eye.add(dx * RAY_DISTANCE, dy * RAY_DISTANCE, dz * RAY_DISTANCE);
        return box.clip(eye, end).isPresent();
    }

    /** Closest point on an axis-aligned box to a world point. */
    private static Vec3 closestPointOnBox(Vec3 eye, AABB box) {
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        return new Vec3(x, y, z);
    }

    /** Wall check: eye→point raycast, {@code MISS} means clear line. */
    private static boolean isVisible(LocalPlayer player, Vec3 from, Vec3 to) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;
        HitResult r = mc.level.clip(new ClipContext(
                from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        return r.getType() == HitResult.Type.MISS;
    }

    private static float clampPitch(float p) {
        return Mth.clamp(p, -89.5F, 89.5F);
    }

    @Override
    public void reset() {
        initialized = false;
        acquireDelay = 0;
        lastTarget = null;
            hasLock = false;
        lockedYaw = 0.0F;
        lockedPitch = 0.0F;
        tremorPhase = 0.0F;
        idlePhase = 0.0F;
        overshootCooldown = 0;
        overshootBias = 0.0F;
    }
}
