package win.furryhook.utils.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra flight prediction, ported 1:1 from javelin's {@code tech.javelin.utility.predict.PredictUtils}.
 *
 * <p>It runs vanilla's elytra travel step <em>once</em> off the entity's current velocity and its
 * previous-tick look angles, then extrapolates that single step linearly by {@code ticks} and lifts
 * the result by 0.35 so the point sits on the body instead of the feet. It is deliberately not an
 * iterative simulation — javelin's aim/attack/speed behaviour depends on this exact shape.
 */
public final class PredictUtils {
    private PredictUtils() {
    }

    /**
     * @param pos   the base position to project forward (javelin always passes {@code target.position()})
     * @param ticks how far ahead to project; fractional values are meaningful
     * @return {@code pos} unchanged while the entity is basically stationary, otherwise the predicted point
     */
    public static Vec3 predict(LivingEntity entity, Vec3 pos, float ticks) {
        if (Math.hypot(entity.getX() - entity.xOld, entity.getZ() - entity.zOld) * 20.0D <= 5.0D
                && (entity.getY() - entity.yOld) * 20.0D <= 5.0D) {
            return pos;
        }

        float prevPitchRad = (entity.getXRot() + (entity.xRotO - entity.getXRot())) * 0.017453292F;
        float prevYawRad = -(entity.getYRot() + (entity.yHeadRotO - entity.getYRot())) * 0.017453292F;
        float cosYaw = Mth.cos(prevYawRad);
        float sinYaw = Mth.sin(prevYawRad);
        float cosPitch = Mth.cos(prevPitchRad);
        float sinPitch = Mth.sin(prevPitchRad);

        Vec3 motion = entity.getDeltaMovement();
        Vec3 look = new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);

        float pitchRad = (float) (entity.getXRot() * 0.017453293005625408D);
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double motionHorizontal = motion.horizontalDistance();

        boolean falling = motion.y <= 0.0D;
        double gravity = falling && entity.hasEffect(MobEffects.SLOW_FALLING)
                ? Math.min(entity.getGravity(), 0.01D)
                : entity.getGravity();
        double pitchFactor = Mth.square(Math.cos(pitchRad));

        motion = motion.add(0.0D, gravity * (-1.0D + pitchFactor * 0.75D), 0.0D);

        double delta;
        if (motion.y < 0.0D && lookHorizontal > 0.0D) {
            delta = motion.y * -0.1D * pitchFactor;
            motion = motion.add(look.x * delta / lookHorizontal, delta, look.z * delta / lookHorizontal);
        }

        if (pitchRad < 0.0F && lookHorizontal > 0.0D) {
            delta = motionHorizontal * (-Mth.sin(pitchRad)) * 0.04D;
            motion = motion.add(-look.x * delta / lookHorizontal, delta * 3.2D, -look.z * delta / lookHorizontal);
        }

        if (lookHorizontal > 0.0D) {
            motion = motion.add(
                    (look.x / lookHorizontal * motionHorizontal - motion.x) * 0.1D,
                    0.0D,
                    (look.z / lookHorizontal * motionHorizontal - motion.z) * 0.1D
            );
        }

        Vec3 step = motion.multiply(0.9900000095367432D, 0.9800000190734863D, 0.9900000095367432D);
        return pos.add(step.scale(ticks)).add(0.0D, 0.3499999940395355D, 0.0D);
    }
}
