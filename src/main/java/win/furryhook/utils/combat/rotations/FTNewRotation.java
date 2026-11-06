package win.furryhook.utils.combat.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;
import win.furryhook.utils.combat.AuraUtil;

/**
 * FT-New rotation - advanced rotation with smooth aiming and prediction
 * Simplified implementation using FurryHook infrastructure
 */
public class FTNewRotation implements AuraRotation {
    
    private double reach = 3.0;
    private boolean throughWalls = false;
    
    // Smoothing parameters
    private float smoothness = 0.35f;
    private float prediction = 2.0f;
    
    // State
    private float lastYaw;
    private float lastPitch;
    private boolean initialized = false;
    
    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (player == null || target == null) return;
        
        // Calculate predicted position
        Vec3 motion = target.getDeltaMovement();
        Vec3 predictedPos = targetEyePos.add(
            motion.x * prediction,
            motion.y * prediction,
            motion.z * prediction
        );
        
        // Calculate ideal rotation
        float[] ideal = calcAngle(player.getEyePosition(), predictedPos);
        
        // Apply smoothing
        if (!initialized) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            initialized = true;
        }
        
        float deltaYaw = wrapDegrees(ideal[0] - lastYaw);
        float deltaPitch = ideal[1] - lastPitch;
        
        float targetYaw = lastYaw + deltaYaw * smoothness;
        float targetPitch = lastPitch + deltaPitch * smoothness;
        
        // Apply rotation via RotationContext
        RotationContext.setRotation(targetYaw, targetPitch);
        
        lastYaw = targetYaw;
        lastPitch = targetPitch;
    }
    
    @Override
    public void reset() {
        initialized = false;
    }
    
    public void setReach(double reach) {
        this.reach = reach;
    }
    
    public void setThroughWalls(boolean throughWalls) {
        this.throughWalls = throughWalls;
    }
    
    public void setSmoothness(float smoothness) {
        this.smoothness = Math.max(0.1f, Math.min(1.0f, smoothness));
    }
    
    public void setPrediction(float prediction) {
        this.prediction = Math.max(0, Math.min(5.0f, prediction));
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
