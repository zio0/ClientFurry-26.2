package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Ported from nuclear's AttackAura "Sloth" mode: purely legit, the aim is
 * never spoofed toward the target — attacks land only when the player's own
 * view is already close enough (gated by {@code AuraFeature}'s distance/ray
 * checks).
 */
public final class BMWRotation implements AuraRotation {
    @Override
    public void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely) {
        // Intentionally left blank: rotation always stays the player's real view.
    }

    @Override
    public void reset() {
    }
}
