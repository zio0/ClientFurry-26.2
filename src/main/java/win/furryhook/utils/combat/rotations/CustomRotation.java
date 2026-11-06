package win.furryhook.utils.combat.rotations;

import win.furryhook.context.RotationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.utils.combat.AuraUtil;

/**
 * Custom rotation - configurable presets (Legit, Balanced, Aggressive, Smooth)
 * Simplified implementation using FurryHook infrastructure
 */
public class CustomRotation implements AuraRotation {
    
    private double reach = 3.0;
    private boolean throughWalls = false;
    
    // Preset parameters
    private String preset = "Balanced";
    private float smoothness = 0.5f;
    private float rotationSpeed = 1.0f;
    private float prediction = 2.0f;
    private float aimHeight = 0.6f; // 60% of entity height
    
    // State
    private float lastYaw;
    private float lastPitch;
    private boolean initialized = false;
    
    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (player == null || target == null) return;
        
        // Calculate aim point based on preset
        Vec3 aimPoint = targetEyePos.subtract(0, target.getBbHeight() * (1.0 - aimHeight), 0);
        
        // Add prediction
        Vec3 motion = target.getDeltaMovement();
        aimPoint = aimPoint.add(
            motion.x * prediction,
            motion.y * prediction,
            motion.z * prediction
        );
        
        // Calculate ideal rotation
        float[] ideal = calcAngle(player.getEyePosition(), aimPoint);
        
        // Apply smoothing
        if (!initialized) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
            initialized = true;
        }
        
        float deltaYaw = wrapDegrees(ideal[0] - lastYaw);
        float deltaPitch = ideal[1] - lastPitch;
        
        // Apply rotation speed
        deltaYaw *= rotationSpeed;
        deltaPitch *= rotationSpeed;
        
        // Apply smoothing
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
    
    public void loadPreset(String presetName) {
        this.preset = presetName;
        
        switch (presetName.toLowerCase()) {
            case "legit":
                this.smoothness = 0.35f;
                this.rotationSpeed = 0.8f;
                this.prediction = 1.5f;
                this.aimHeight = 0.6f;
                break;
            case "balanced":
                this.smoothness = 0.5f;
                this.rotationSpeed = 1.0f;
                this.prediction = 2.0f;
                this.aimHeight = 0.6f;
                break;
            case "aggressive":
                this.smoothness = 0.7f;
                this.rotationSpeed = 1.5f;
                this.prediction = 3.0f;
                this.aimHeight = 0.7f;
                break;
            case "smooth":
                this.smoothness = 0.25f;
                this.rotationSpeed = 0.6f;
                this.prediction = 1.0f;
                this.aimHeight = 0.6f;
                break;
        }
    }
    
    public void setSmoothingFactor(float factor) {
        this.smoothness = Math.max(0.1f, Math.min(1.0f, factor));
    }
    
    public void setRotationSpeed(float speed) {
        this.rotationSpeed = Math.max(0.1f, Math.min(3.0f, speed));
    }
    
    public void setPredictionAmount(float amount) {
        this.prediction = Math.max(0, Math.min(5.0f, amount));
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
