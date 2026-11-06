package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

/**
 * Ported from nuclear's AttackAura "Snap" mode: aims at the target for the
 * first few ticks after acquisition, then releases rotation control back to
 * the player's real view for the rest of the engagement.
 */
public final class SnapRotation implements AuraRotation {
    private static final int SNAP_TICKS = 3;

    private int targetId = Integer.MIN_VALUE;
    private int ticks = 0;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.ticks = 0;
        }

        if (ticks < SNAP_TICKS) {
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

            float step = 1.2F / SNAP_TICKS;
            float yaw = currentYaw + Mth.clamp(deltaYaw * step, -180.0F, 180.0F);
            float pitch = Mth.clamp(currentPitch + deltaPitch * step, -90.0F, 90.0F);

            RotationContext.setRotation(yaw, pitch);
            ticks++;
        } else {
            RotationContext.setRotation(player.getYRot(), player.getXRot());
        }
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.ticks = 0;
    }
}
