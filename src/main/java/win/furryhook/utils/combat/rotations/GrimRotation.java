package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

/**
 * Ported from nuclear's AttackAura "Grim" mode: a direct, uncapped smooth
 * turn toward the target every tick (no acquisition delay, no leading).
 */
public final class GrimRotation implements AuraRotation {
    private static final float TURN_SPEED = 0.55F;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            return;
        }

        Vec3 eye = player.getEyePosition();
        double dx = targetEyePos.x - eye.x;
        double dy = (targetEyePos.y + target.getBbHeight() / 2.0D)
                - (eye.y + player.getBbHeight() / 2.0D);
        double dz = targetEyePos.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float idealYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float idealPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90.0F, 90.0F);

        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        float deltaYaw = Mth.wrapDegrees(idealYaw - currentYaw);
        float deltaPitch = idealPitch - currentPitch;

        float yaw = currentYaw + deltaYaw * TURN_SPEED;
        float pitch = Mth.clamp(currentPitch + deltaPitch * TURN_SPEED, -90.0F, 90.0F);

        RotationContext.setRotation(yaw, pitch);
    }

    @Override
    public void reset() {
    }
}
