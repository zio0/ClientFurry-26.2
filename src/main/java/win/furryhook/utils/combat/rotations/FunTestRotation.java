package win.furryhook.utils.combat.rotations;

import win.furryhook.context.RotationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.utils.combat.AuraUtil;

import java.util.Random;

/**
 * FunTest rotation - adaptive jitter aim pattern for natural look
 * Simplified implementation using FurryHook infrastructure
 */
public class FunTestRotation implements AuraRotation {
    
    private final Random random = new Random();
    
    private double reach = 3.0;
    private boolean throughWalls = false;
    
    // Jitter parameters
    private float jitterStrength = 0.8f;
    private float smoothing = 0.4f;
    private long lastJitterTime = 0;
    private float jitterFrequency = 150; // ms
    
    // Jitter offsets
    private float jitterYaw = 0;
    private float jitterPitch = 0;
    private int jitterPhase = 0;
    
    // Attack counter for pitch randomization
    private int attackCount = 0;
    
    // State
    private float lastYaw;
    private float lastPitch;
    private boolean initialized = false;
    
    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (player == null || target == null) return;
        
        // Calculate ideal rotation
        float[] ideal = calcAngle(player.getEyePosition(), targetEyePos);
        
        // Update jitter if needed
        long now = System.currentTimeMillis();
        if (now - lastJitterTime >= jitterFrequency) {
            updateJitter(player, target);
            lastJitterTime = now;
        }
        
        // Apply jitter
        float targetYaw = ideal[0] + jitterYaw;
        float targetPitch = ideal[1] + jitterPitch;
        
        // Apply smoothing
        if (!initialized) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            initialized = true;
        }
        
        float deltaYaw = wrapDegrees(targetYaw - lastYaw);
        float deltaPitch = targetPitch - lastPitch;
        
        float smoothedYaw = lastYaw + deltaYaw * smoothing;
        float smoothedPitch = lastPitch + deltaPitch * smoothing;
        
        // Clamp pitch
        smoothedPitch = Math.max(-90, Math.min(90, smoothedPitch));
        
        // Apply rotation via RotationContext
        RotationContext.setRotation(smoothedYaw, smoothedPitch);
        
        lastYaw = smoothedYaw;
        lastPitch = smoothedPitch;
    }
    
    private void updateJitter(LocalPlayer player, LivingEntity target) {
        // Cycle through phases for natural movement
        jitterPhase = (jitterPhase + 1) % 4;
        
        // Distance-based adaptive factor
        float distance = player.distanceTo(target);
        float adaptiveFactor = Math.min(1.0f, distance / 4.0f) * 0.5f + 0.5f;
        
        // Update yaw every time
        switch (jitterPhase) {
            case 0: // Right
            case 2:
                jitterYaw = random.nextFloat() * jitterStrength * adaptiveFactor;
                break;
            case 1: // Left
            case 3:
                jitterYaw = -random.nextFloat() * jitterStrength * adaptiveFactor;
                break;
        }
        
        // Update pitch only every 2 attacks (when attackCount is even)
        if (attackCount % 2 == 0) {
            if (jitterPhase < 2) {
                // Up
                jitterPitch = -random.nextFloat() * jitterStrength * 0.5f * adaptiveFactor;
            } else {
                // Down
                jitterPitch = random.nextFloat() * jitterStrength * 0.5f * adaptiveFactor;
            }
        }
        // else keep previous pitch value
    }
    
    @Override
    public void reset() {
        initialized = false;
        jitterYaw = 0;
        jitterPitch = 0;
        jitterPhase = 0;
        attackCount = 0;
    }
    
    @Override
    public void onAttack() {
        attackCount++;
    }
    
    public void setReach(double reach) {
        this.reach = reach;
    }
    
    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }
    
    public void setJitterStrength(float strength) {
        this.jitterStrength = Math.max(0, Math.min(3.0f, strength));
    }
    
    public void setJitterFrequency(float frequency) {
        this.jitterFrequency = frequency;
    }
    
    public void setSmoothing(float smoothing) {
        this.smoothing = Math.max(0.1f, Math.min(1.0f, smoothing));
    }
    
    private static float wrapDegrees(float angle) {
        angle = angle % 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }
    
    private static float[] calcAngle(Vec3 from, Vec3 to) {
        double diffX = to.x - from.x;
        double diffY = to.y - from.y;
        double diffZ = to.z - from.z;
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) -(Math.atan2(diffY, dist) * 180.0 / Math.PI);
        
        return new float[]{yaw, pitch};
    }
}
