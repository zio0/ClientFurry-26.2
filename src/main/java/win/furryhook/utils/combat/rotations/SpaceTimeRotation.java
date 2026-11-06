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
 * SpaceTime Rotation - Advanced rotation system with physics-based movement.
 * 
 * Features:
 * - Acceleration and deceleration physics
 * - Aim fatigue over time
 * - Intelligent pauses and micro-corrections
 * - Multipoint targeting with body zone changes
 * - Aim drift simulation
 * - Movement-aware behavior (both players moving = more legit)
 * - Distance-based pattern blending
 * - Reaction delays after hits
 * - Predictive leading
 * - Breathing simulation
 * - Velocity-based smoothing with inertia
 */
public final class SpaceTimeRotation implements AuraRotation {

    private float lastYaw = 0.0F;
    private float lastPitch = 0.0F;
    private boolean initialized = false;
    private float acceleration = 0.0F;
    private boolean isBack = false;
    private float aimFatigue = 0.0F;
    private long lastAimStartTime = 0L;
    
    // Body targeting
    private float bodyYawOffset = 0.0F;
    private float bodyPitchOffset = 0.0F;
    private int bodyZoneChangeTicks = 0;
    
    // Pauses and delays
    private int aimPauseTicks = 0;
    private int reactionDelay = 0;
    private int microCorrectionTimer = 0;
    
    // Drift simulation
    private int driftRetargetTicks = 0;
    private float aimDriftYaw = 0.0F;
    private float aimDriftPitch = 0.0F;
    private float driftTargetYaw = 0.0F;
    private float driftTargetPitch = 0.0F;
    
    // Physics
    private float yawAcceleration = 0.0F;
    private float pitchAcceleration = 0.0F;
    private float prevTargetYaw = 0.0F;
    private float prevTargetPitch = 0.0F;
    private float velocityYaw = 0.0F;
    private float velocityPitch = 0.0F;
    
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    
    private double reach = 3.0D;
    private boolean throughWalls;
    
    /**
     * Distance-based patterns: [unused, unused, speed, unused, unused, unused, jitterMult, unused]
     * Each pattern is optimized for different distances
     */
    private final float[][] patterns = {
        {0, 0, 70.0f, 1.8f, 0, 0, 0.75f, 2},  // Close range
        {0, 0, 44.0f, 3.0f, 0, 0, 0.65f, 3},  // Medium-close
        {0, 0, 28.0f, 4.0f, 0, 0, 0.50f, 4},  // Medium
        {0, 0, 90.0f, 1.2f, 0, 0, 0.85f, 2},  // Very close
        {0, 0, 20.0f, 5.5f, 0, 0, 0.40f, 5}   // Far
    };
    
    public void setReach(double reach) {
        this.reach = reach;
    }
    
    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive() || player == null) {
            return;
        }

        if (!initialized) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            Vec3 eye = player.getEyePosition(1.0F);
            Vec3 diff = targetEyePos.subtract(eye);
            double horizontal = Math.hypot(diff.x, diff.z);
            prevTargetYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
            prevTargetPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, horizontal)), -90.0F, 90.0F);
            initialized = true;
            lastAimStartTime = System.currentTimeMillis();
        }

        float dist = (float) player.distanceTo(target);
        long now = System.currentTimeMillis();
        
        if (lastAimStartTime == 0L) {
            lastAimStartTime = now;
        }
        
        float timeFighting = (now - lastAimStartTime) / 1000.0F;
        aimFatigue = Mth.clamp(timeFighting / 300.0F, 0.0F, 0.42F);

        boolean playerMoving = player.getDeltaMovement().horizontalDistanceSqr() > 0.003;
        boolean targetMoving = target.getDeltaMovement().horizontalDistanceSqr() > 0.003;
        boolean bothMoving = playerMoving && targetMoving;
        boolean anyMoving = playerMoving || targetMoving;
        boolean inHitRange = dist <= 3.2F;

        // Multipoint: when both moving, aim almost center
        if (--bodyZoneChangeTicks <= 0) {
            if (bothMoving || inHitRange) {
                bodyYawOffset = (random.nextFloat() - 0.5F) * 1.6F;
                bodyPitchOffset = (random.nextFloat() - 0.5F) * 1.4F;
                bodyZoneChangeTicks = 16 + random.nextInt(22);
            } else {
                bodyYawOffset = (random.nextFloat() - 0.5F) * 4.5F;
                float[] pitches = {-3.0F, -1.0F, 0.0F, 1.5F, 3.5F};
                bodyPitchOffset = pitches[random.nextInt(pitches.length)];
                bodyPitchOffset += (random.nextFloat() - 0.5F) * 0.8F;
                bodyZoneChangeTicks = 18 + random.nextInt(28);
            }
        }

        // Pattern blending based on distance
        int i1 = 0, i2 = 0;
        float d1 = Float.MAX_VALUE, d2 = Float.MAX_VALUE;
        for (int i = 0; i < patterns.length; i++) {
            float diff = Math.abs(patterns[i][3] - dist);
            if (diff < d1) {
                d2 = d1;
                i2 = i1;
                d1 = diff;
                i1 = i;
            } else if (diff < d2) {
                d2 = diff;
                i2 = i;
            }
        }
        
        float blend = (d1 + d2 == 0) ? 0.5f : d2 / (d1 + d2);
        float speed = patterns[i1][2] * (1 - blend) + patterns[i2][2] * blend;
        float jitterMult = patterns[i1][6] * (1 - blend) + patterns[i2][6] * blend;
        
        speed *= (1.0F - aimFatigue * 0.40F);
        
        // Both moving: slower (anti-ban), without separate boosts
        if (bothMoving) {
            speed *= 0.62F;
        } else if (anyMoving) {
            speed *= 0.80F;
        }
        
        if (timeFighting > 90.0F) speed *= 0.84F;
        if (timeFighting > 150.0F) speed *= 0.80F;

        // Calculate aim angle
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 targetVec = targetEyePos != null ? targetEyePos : target.getEyePosition(1.0F);
        Vec3 diff = targetVec.subtract(eye);
        double horizontal = Math.hypot(diff.x, diff.z);
        float targetYaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0F;
        float targetPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(diff.y, horizontal)), -90.0F, 90.0F);

        // Handle aim pause (drift during pause)
        if (aimPauseTicks > 0) {
            if (inHitRange && aimPauseTicks > 1) {
                aimPauseTicks = 1;
            }
            aimPauseTicks--;
            
            if (--driftRetargetTicks <= 0) {
                driftTargetYaw = (float) (random.nextGaussian() * (bothMoving ? 0.12F : 0.22F));
                driftTargetPitch = (float) (random.nextGaussian() * (bothMoving ? 0.08F : 0.14F));
                driftRetargetTicks = 8 + random.nextInt(12);
            }
            
            aimDriftYaw += (driftTargetYaw - aimDriftYaw) * 0.05F;
            aimDriftPitch += (driftTargetPitch - aimDriftPitch) * 0.05F;
            
            float pauseYaw = lastYaw + aimDriftYaw * 0.10F;
            float pausePitch = lastPitch + aimDriftPitch * 0.10F;
            
            double gcd = RotationContext.gcdStep();
            pauseYaw = quantizeGCD(pauseYaw, lastYaw, gcd);
            pausePitch = quantizeGCD(pausePitch, lastPitch, gcd);
            
            pausePitch = Mth.clamp(pausePitch, -90.0F, 90.0F);
            
            RotationContext.setRotation(pauseYaw, pausePitch);
            lastYaw = pauseYaw;
            lastPitch = pausePitch;
            return;
        }

        // Random pause chance
        float pauseChance = bothMoving ? 0.02F : 0.035F;
        if (inHitRange) pauseChance = 0.01F;
        
        if (random.nextFloat() < pauseChance && Math.abs(Mth.wrapDegrees(targetYaw - lastYaw)) < 12.0F) {
            aimPauseTicks = 1 + (bothMoving ? 0 : random.nextInt(2));
        }
        
        if (!bothMoving && !inHitRange && random.nextFloat() < 0.006F
                && Math.abs(Mth.wrapDegrees(targetYaw - lastYaw)) < 16.0F) {
            aimPauseTicks = 6 + random.nextInt(10);
        }

        // Acceleration physics
        if (isBack) {
            float angleDiff = Math.abs(Mth.wrapDegrees(targetYaw - lastYaw));
            acceleration -= angleDiff > 70.0F ? 0.11F : (speed / 1400.0F);
            if (acceleration <= 0.0F) {
                acceleration = 0.0F;
                isBack = false;
            }
        } else {
            acceleration += (speed / 1800.0F) * (0.88F + random.nextFloat() * 0.12F);
            float maxAcc = bothMoving ? 0.12F : 0.17F;
            maxAcc = Math.min(maxAcc, speed / 2500.0F + 0.03F);
            
            boolean onTarget = hits(player, target, lastYaw, lastPitch);
            
            if (acceleration >= maxAcc || onTarget) {
                isBack = true;
            }
        }

        float currentAcc = Mth.clamp(acceleration, 0.0F, 1.0F);
        
        if (player.hurtTime > 0) currentAcc *= 0.32F;
        if (reactionDelay > 0) {
            reactionDelay--;
            currentAcc *= 0.28F;
        }
        
        if (bothMoving) {
            currentAcc *= 0.58F + random.nextFloat() * 0.08F;
        } else if (anyMoving) {
            currentAcc *= 0.78F;
        }
        
        if (timeFighting > 90.0F) currentAcc *= 0.84F;
        if (timeFighting > 150.0F) currentAcc *= 0.80F;

        // Drift retargeting
        if (--driftRetargetTicks <= 0) {
            float j = bothMoving ? 0.35F : 0.75F;
            if (inHitRange) j *= 0.5F;
            driftTargetYaw = (float) (random.nextGaussian() * j * jitterMult);
            driftTargetPitch = (float) (random.nextGaussian() * j * 0.6F * jitterMult);
            driftRetargetTicks = bothMoving ? (7 + random.nextInt(12)) : (9 + random.nextInt(16));
        }
        
        aimDriftYaw += (driftTargetYaw - aimDriftYaw) * 0.08F;
        aimDriftPitch += (driftTargetPitch - aimDriftPitch) * 0.08F;

        // Micro corrections
        boolean micro = false;
        if (--microCorrectionTimer <= 0 && Math.abs(Mth.wrapDegrees(targetYaw - lastYaw)) < 8.0F) {
            micro = true;
            microCorrectionTimer = bothMoving ? (8 + random.nextInt(14)) : (12 + random.nextInt(18));
        }

        // Apply offsets
        float useBody = (bothMoving || inHitRange) ? 0.35F : 1.0F;
        float useDrift = micro ? 1.1F : 1.0F;
        if (bothMoving || inHitRange) useDrift *= 0.30F;
        
        float aimYaw = targetYaw + bodyYawOffset * useBody + aimDriftYaw * useDrift;
        float aimPitch = targetPitch + bodyPitchOffset * useBody + aimDriftPitch * useDrift;
        
        float deltaYaw = Mth.wrapDegrees(aimYaw - lastYaw);
        float deltaPitch = aimPitch - lastPitch;
        
        if (micro) {
            deltaYaw *= 0.35F;
            deltaPitch *= 0.35F;
        }

        float absYaw = Math.abs(deltaYaw);
        float absPitch = Math.abs(deltaPitch);
        
        if (absYaw > 28.0F || absPitch > 18.0F) {
            currentAcc = Math.min(1.0F, currentAcc + 0.08F);
        }

        boolean hasTrace = hits(player, target, lastYaw, lastPitch);
        
        // Already on hitbox - hold, don't overshoot
        if (hasTrace && absYaw < 5.0F && absPitch < 4.0F) {
            deltaYaw *= 0.40F;
            deltaPitch *= 0.40F;
            currentAcc *= 0.65F;
        }

        // Predictive leading
        if (reactionDelay <= 0) {
            float velocityYaw = Mth.wrapDegrees(targetYaw - prevTargetYaw);
            float velocityPitch = targetPitch - prevTargetPitch;
            float lead = bothMoving ? 0.16F : Mth.clamp(dist / 5.5F, 0.10F, 0.22F);
            float clamp = bothMoving ? 4.0F : 3.0F;
            deltaYaw += Mth.clamp(velocityYaw * lead, -clamp, clamp);
            deltaPitch += Mth.clamp(velocityPitch * lead * 0.55F, -2.0F, 2.0F);
        }
        
        prevTargetYaw = targetYaw;
        prevTargetPitch = targetPitch;

        // Breathing simulation
        float breathTime = (now % 100000L) / 1000.0F;
        float breathMul = bothMoving ? 0.70F : 1.15F;
        deltaYaw += Mth.sin(breathTime * 0.55F) * 0.09F * jitterMult * breathMul;
        deltaPitch += Mth.cos(breathTime * 0.42F) * 0.06F * jitterMult * breathMul;

        // Smoothing with velocity
        float smooth = Mth.clamp(currentAcc, 0.009F, 1.0F) * (1.0F - aimFatigue * 0.28F);
        float targetVelYaw = deltaYaw * smooth * (0.76F + random.nextFloat() * 0.10F);
        float targetVelPitch = deltaPitch * smooth * (0.74F + random.nextFloat() * 0.10F);
        
        if (micro) {
            targetVelYaw *= 0.55F;
            targetVelPitch *= 0.55F;
        }

        // Inertia
        float inertia = bothMoving ? 0.68F : 0.58F;
        velocityYaw = velocityYaw * inertia + targetVelYaw * (1.0F - inertia);
        velocityPitch = velocityPitch * inertia + targetVelPitch * (1.0F - inertia);

        // Noise
        float noiseScale = bothMoving ? 0.018F : 0.028F;
        yawAcceleration = yawAcceleration * 0.86F + (float) (random.nextGaussian() * noiseScale * jitterMult);
        pitchAcceleration = pitchAcceleration * 0.86F + (float) (random.nextGaussian() * noiseScale * 0.75F * jitterMult);

        float newYaw = lastYaw + velocityYaw + yawAcceleration;
        float newPitch = lastPitch + velocityPitch + pitchAcceleration;

        // Step limit (both moving: hard cap, no 26°)
        float maxStep = bothMoving
                ? (5.0F + 12.0F * currentAcc)
                : (7.0F + 19.0F * currentAcc);
        maxStep = Mth.clamp(maxStep, 2.5F, bothMoving ? 18.0F : 26.0F);
        
        if (absYaw > 30.0F) maxStep *= 1.08F;
        if (timeFighting > 90.0F) maxStep *= 0.90F;
        if (timeFighting > 150.0F) maxStep *= 0.86F;

        newYaw = lastYaw + Mth.clamp(newYaw - lastYaw, -maxStep, maxStep);
        newPitch = lastPitch + Mth.clamp(newPitch - lastPitch, -maxStep * 0.60F, maxStep * 0.60F);

        // GCD snap
        double gcd = RotationContext.gcdStep();
        newYaw = quantizeGCD(newYaw, lastYaw, gcd);
        newPitch = quantizeGCD(newPitch, lastPitch, gcd);
        
        newPitch = Mth.clamp(newPitch, -90.0F, 90.0F);

        RotationContext.setRotation(newYaw, newPitch);
        lastYaw = newYaw;
        lastPitch = newPitch;
    }

    @Override
    public void onAttack() {
        bodyYawOffset *= 0.25F;
        bodyPitchOffset *= 0.25F;
        bodyZoneChangeTicks = 0;
        reactionDelay = 2 + random.nextInt(3);
        isBack = true;
        acceleration *= 0.42F;
        velocityYaw *= 0.55F;
        velocityPitch *= 0.55F;
    }

    @Override
    public void reset() {
        initialized = false;
        lastAimStartTime = 0L;
        aimFatigue = 0.0F;
        velocityYaw = 0.0F;
        velocityPitch = 0.0F;
        aimPauseTicks = 0;
        reactionDelay = 0;
        acceleration = 0.0F;
        isBack = false;
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

    private float quantizeGCD(float newValue, float oldValue, double gcd) {
        if (gcd < 1.0E-4D) {
            return newValue;
        }
        float delta = newValue - oldValue;
        float snapped = oldValue + (float) (Math.round(delta / gcd) * gcd);
        return snapped;
    }

    /**
     * Checks if rotation is currently returning to natural view.
     * SpaceTime doesn't use explicit return curve, always returns false.
     */
    public boolean isReturning() {
        return false;
    }
}
