package win.furryhook.utils.combat.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.utils.math.NoiseUtil;

import java.util.LinkedList;
import java.util.concurrent.ThreadLocalRandom;

public final class LegitRotation implements AuraRotation {
    private final LinkedList<Vec3> targetPositionHistory = new LinkedList<>();
    private float remainderYaw = 0.0F;
    private float remainderPitch = 0.0F;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        long now = System.currentTimeMillis();

        // Maintain historical positions for reaction delay (~150-200ms)
        targetPositionHistory.addLast(target.getDeltaMovement());
        while (targetPositionHistory.size() > 7) {
            targetPositionHistory.removeFirst();
        }

        // 1. Human Perceptual Latency (reaction delay buffer)
        Vec3 targetPos = target.position();
        if (!targetPositionHistory.isEmpty()) {
            int delayIndex = Math.min(2, targetPositionHistory.size() - 1);
            Vec3 historicVel = targetPositionHistory.get(delayIndex);
            targetPos = target.position().subtract(historicVel.scale(0.35D));
        }

        // 2. Wandering Aim-Point (organically float target height/offsets)
        double noiseTime = now / 450.0D;
        float wanderY = NoiseUtil.perlin1D(noiseTime) * 0.10F;
        float wanderX = NoiseUtil.perlin2D(noiseTime, 10.0D) * 0.06F;
        float wanderZ = NoiseUtil.perlin2D(noiseTime, 20.0D) * 0.06F;

        double targetY = targetPos.y + target.getBbHeight() * (0.5F + wanderY);
        Vec3 aimVec = new Vec3(targetPos.x + wanderX, targetY, targetPos.z + wanderZ).subtract(player.getEyePosition(1.0F));
        double horizDist = Math.hypot(aimVec.x, aimVec.z);

        float rawYaw = (float) Math.toDegrees(Math.atan2(-aimVec.x, aimVec.z));
        float rawPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(aimVec.y, horizDist)), -90.0D, 90.0D);

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        float deltaYaw = Mth.wrapDegrees(rawYaw - currentYaw);
        float deltaPitch = rawPitch - currentPitch;
        float totalDist = (float) Math.hypot(deltaYaw, deltaPitch);

        // 3. Dynamic Human Curve & Speed Scaling (Main yaw movement)
        float maxStepYaw = 35.0F;
        if (totalDist < 5.0F) {
            maxStepYaw = randomLerp(12.0F, 18.0F);
        } else if (totalDist > 25.0F) {
            maxStepYaw = randomLerp(38.0F, 48.0F);
        }

        float stepYaw = Mth.clamp(deltaYaw * randomLerp(0.45F, 0.65F), -maxStepYaw, maxStepYaw);

        // Randomized Arc trajectory with organic curve jitter (hand wrist arc + finger jitter)
        double arcNoiseTime = now / 180.0D;
        float baseArcStrength = 0.35F + NoiseUtil.perlin1D(arcNoiseTime) * 0.35F;
        float arcJitter = randomLerp(-0.12F, 0.12F) + NoiseUtil.perlin2D(now / 75.0D, 5.0D) * 0.20F; // High-frequency wrist/finger jitter along the curve
        float arcMultiplier = baseArcStrength + arcJitter;
        float arcBias = (float) Math.sin(Math.toRadians(deltaYaw)) * (stepYaw * arcMultiplier);

        // Pitch follows Yaw at only 10-20% speed (purely for sync & natural wrist tilt)
        float pitchSyncRatio = randomLerp(0.10F, 0.20F);
        float stepPitch = deltaPitch * pitchSyncRatio + arcBias * (0.15F + arcJitter * 0.5F);

        // 4. Perlin Noise Tremor + Micro-Jitter (Organic micro-shakes along trajectory)
        double tremorTime = now / 110.0D;
        float jitterX = randomLerp(-0.15F, 0.15F);
        float jitterY = randomLerp(-0.08F, 0.08F);
        float tremorYaw = NoiseUtil.perlin2D(tremorTime, 1.0D) * 0.40F + jitterX;
        float tremorPitch = NoiseUtil.perlin2D(tremorTime, 2.0D) * 0.15F + jitterY; // damp tremor on pitch

        stepYaw += tremorYaw;
        stepPitch += tremorPitch;

        // 5. Hardware Sensor Simulation (GCD Sensitivity Alignment)
        float gcd = getGCDValue();
        float targetStepYaw = stepYaw + remainderYaw;
        float targetStepPitch = stepPitch + remainderPitch;

        int mX = Math.round(targetStepYaw / gcd);
        int mY = Math.round(targetStepPitch / gcd);

        float finalDeltaYaw = mX * gcd;
        float finalDeltaPitch = mY * gcd;

        remainderYaw = targetStepYaw - finalDeltaYaw;
        remainderPitch = targetStepPitch - finalDeltaPitch;

        RotationContext.setRotation(currentYaw + finalDeltaYaw, Mth.clamp(currentPitch + finalDeltaPitch, -90.0F, 90.0F));
    }

    @Override
    public void reset() {
        targetPositionHistory.clear();
        remainderYaw = 0.0F;
        remainderPitch = 0.0F;
    }

    private static float getGCDValue() {
        float sensitivity = Minecraft.getInstance().options.sensitivity().get().floatValue() * 0.6F + 0.2F;
        return sensitivity * sensitivity * sensitivity * 8.0F * 0.15F;
    }

    private static float randomLerp(float min, float max) {
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }
}
