package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.RotationContext;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ported from nuclear's AttackAura "HolyWorld" mode: a spring/damper based
 * rotation that accelerates toward the target, tightens up right before an
 * attack lands, and snaps a final correction in when very close.
 *
 * <p>On top of the original velocity model this adds human polish so the aim
 * reads like a mouse and not a formula:
 * <ul>
 *   <li>a per-tick {@code 0.85} lerp pull toward the target once close (the classic
 *       "not instant, not lagging" feel), weaker while tracking, full strength right
 *       before a swing;</li>
 *   <li>sub-gcd micro-jitter — the aim never rests grid-aligned, it wanders like a
 *       hand holding a mouse, and the jitter collapses right before the attack so it
 *       doesn't cost hits;</li>
 *   <li>a stable per-target aim bias (torso height + slight lean) — people lock onto
 *       the same spot on a given target instead of the exact center pixel;</li>
 *   <li>anti-oscillation damping so the captured spring does not ring past the hitbox
 *       and away from the target (a real source of aimbot misses).</li>
 * </ul>
 */
public final class HolyWorldRotation implements AuraRotation {
    /** Per-tick lerp pull toward the target once close. */
    private static final float HUMAN_LERP = 0.85F;
    /** Degrees the aim may wander off the true angle each tick; sub-gcd noise. */
    private static final float JITTER_YAW = 0.09F;
    private static final float JITTER_PITCH = 0.05F;

    private int targetId = Integer.MIN_VALUE;
    private float velYaw;
    private float velPitch;
    /** Per-target aim bias: torso fraction and a slight horizontal lean. */
    private float aimHeight;
    private float aimBiasX;
    private float aimBiasZ;

    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        if (target == null || !target.isAlive()) {
            reset();
            return;
        }

        if (this.targetId != target.getId()) {
            this.targetId = target.getId();
            this.velYaw = 0.0F;
            this.velPitch = 0.0F;
            // Random but stable for the whole engagement: same spot on the same target.
            this.aimHeight = 0.42F + ThreadLocalRandom.current().nextFloat() * 0.30F;
            float lean = ThreadLocalRandom.current().nextFloat() * 0.05F;
            this.aimBiasX = (ThreadLocalRandom.current().nextBoolean() ? 1.0F : -1.0F) * lean;
            this.aimBiasZ = (ThreadLocalRandom.current().nextBoolean() ? 1.0F : -1.0F)
                    * (0.03F + ThreadLocalRandom.current().nextFloat() * 0.02F);
        }

        Vec3 eye = player.getEyePosition();
        float currentYaw = RotationContext.isActive() ? RotationContext.getServerYaw() : player.getYRot();
        float currentPitch = RotationContext.isActive() ? RotationContext.getServerPitch() : player.getXRot();

        // Aim at the natural human spot: upper torso of this target, slightly leaned to the side.
        float aimY = attackLikely
                ? target.getEyeHeight() * 0.92F
                : target.getBbHeight() * this.aimHeight;
        Vec3 aim = target.position().add(this.aimBiasX, aimY, this.aimBiasZ);
        if (attackLikely) {
            aim = aim.add(target.getDeltaMovement());
        }

        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        float yawError = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchError = targetPitch - currentPitch;
        float angleDist = (float) Math.sqrt(yawError * yawError + pitchError * pitchError);

        float speedMul = 1.32F;
        float pendulumMul = 1.0F / 1.5F;
        float pitchPendulumMul = 0.5F;
        float catchUp = Mth.clamp(angleDist / 13.33F, 1.0F, 2.56F);

        float spring = 0.125F * catchUp * speedMul * pendulumMul;
        float damping = 0.92F;
        float maxStepYaw = 13.125F * speedMul;
        float maxStepPitch = 8.125F * speedMul * pitchPendulumMul;

        if (attackLikely) {
            float attackSpeedMul = 1.45F;
            spring = 0.144F * catchUp * attackSpeedMul * speedMul;
            damping = 0.94F;
            maxStepYaw = 14.25F * attackSpeedMul * speedMul;
            maxStepPitch = 8.75F * attackSpeedMul * speedMul * pitchPendulumMul;
        }

        this.velYaw += yawError * spring;
        this.velPitch += pitchError * spring * 0.72F * pitchPendulumMul;

        // Anti-oscillation: if the velocity is blowing past the target (vel opposes the
        // remaining error), kill it hard — a ringing barrel is how the spring misses.
        if (this.velYaw * yawError < 0.0F) {
            this.velYaw *= 0.55F;
        }
        if (this.velPitch * pitchError < 0.0F) {
            this.velPitch *= 0.55F;
        }

        this.velYaw *= damping;
        this.velPitch *= damping;

        this.velYaw = Mth.clamp(this.velYaw, -maxStepYaw, maxStepYaw);
        this.velPitch = Mth.clamp(this.velPitch, -maxStepPitch, maxStepPitch);

        float yaw = currentYaw + this.velYaw;
        float pitch = Mth.clamp(currentPitch + this.velPitch, -89.9F, 89.9F);

        // Human lerp 0.85: pull the spring result toward the true aim once close. Weak while
        // tracking a moving target (so the chase reads organic), full strength before a swing.
        float close = Mth.clamp((18.0F - angleDist) / 18.0F, 0.0F, 1.0F);
        if (close > 0.0F) {
            float pull = HUMAN_LERP * (0.35F + 0.65F * close) * (attackLikely ? 1.0F : 0.6F);
            yaw = Mth.lerp(pull, yaw, targetYaw);
            pitch = Mth.lerp(pull, pitch, targetPitch);
        }

        // GCD jitter: sub-degree noise so the aim never sits frozen on a sensitivity grid.
        // It collapses right before an attack — looks alive, costs no hits.
        float jitterScale = attackLikely ? 0.35F : 1.0F;
        yaw = Mth.wrapDegrees(yaw
                + (ThreadLocalRandom.current().nextFloat() * 2.0F - 1.0F) * JITTER_YAW * jitterScale);
        pitch = Mth.clamp(pitch
                + (ThreadLocalRandom.current().nextFloat() * 2.0F - 1.0F) * JITTER_PITCH * jitterScale,
                -89.9F, 89.9F);

        RotationContext.setRotation(yaw, pitch);
    }

    @Override
    public void reset() {
        this.targetId = Integer.MIN_VALUE;
        this.velYaw = 0.0F;
        this.velPitch = 0.0F;
    }
}
