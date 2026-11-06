package win.furryhook.mixin.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import win.furryhook.context.RotationContext;
import win.furryhook.event.EventManager;
import win.furryhook.event.Events;
import win.furryhook.event.events.game.FireworkEvent;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.impl.movement.ElytraBoostFeature;

/**
 * Ported from Minced's FireworkRocketEntityMixin. Vanilla applies elytra firework
 * boost with a single {@code setDeltaMovement} call using the shooter's look
 * direction and flat 1.5/1.5 speeds. This redirect swaps in the aura-spoofed
 * rotation (when an aura is controlling) and {@link ElytraBoostFeature}'s
 * per-mode boost vector, then reapplies Minced's addBoost formula:
 *
 *   motion += rot * 0.1 + (rot * boost - motion) * 0.5
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {

    private static final double INERTIA = 0.1D;
    private static final double ACCEL   = 0.5D;
    /** Vanilla's flat firework boost, used when ElytraBoost is off but ElytraTarget redirected. */
    private static final Vec3 VANILLA_BOOST = new Vec3(1.5D, 1.5D, 1.5D);

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void redirectBoostVelocity(LivingEntity shooter, Vec3 originalVelocity) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || shooter != player) {
            shooter.setDeltaMovement(originalVelocity);
            return;
        }

        ElytraBoostFeature.markBoostApplied();

        if (EventManager.hasListeners(FireworkEvent.class)) {
            EventManager.call(Events.FIREWORK.set(player, 1.5F));
        }

        // ElytraTarget deliberately does NOT touch the thrust any more.
        //
        // Grim simulates elytra flight in full, firework boost included: it knows the impulse is
        // `look * 1.5` along the rotation the client sent, and it checks the resulting position
        // every tick. Pushing along an intercept vector instead of the sent look makes every
        // predicted position wrong for as long as the rocket burns — which is exactly why the
        // overtake flagged constantly while ground combat stayed clean.
        //
        // The overtake steers with the rotation instead: the aura aims at the intercept, that
        // rotation is what goes on the wire, and vanilla physics carries the player there on its
        // own. Same flight, fully predictable, nothing to flag.
        ElytraBoostFeature boost = ElytraBoostFeature.getEnabled();
        if (boost == null) {
            shooter.setDeltaMovement(originalVelocity);
            return;
        }

        AuraFeature aura = AuraFeature.getEnabled();
        boolean auraControlling = (aura != null && aura.getTarget() != null) || RotationContext.isActive();

        float yaw   = auraControlling ? RotationContext.getServerYaw()   : player.getYRot();
        float pitch = auraControlling ? RotationContext.getServerPitch() : player.getXRot();

        Vec3 motion = shooter.getDeltaMovement();
        Vec3 rotation = Vec3.directionFromRotation(pitch, yaw);
        Vec3 boostVec = boost.computeBoost(yaw, pitch);

        shooter.setDeltaMovement(
                motion.x + rotation.x * INERTIA + (rotation.x * boostVec.x - motion.x) * ACCEL,
                motion.y + rotation.y * INERTIA + (rotation.y * boostVec.y - motion.y) * ACCEL,
                motion.z + rotation.z * INERTIA + (rotation.z * boostVec.z - motion.z) * ACCEL
        );
    }
}
