package win.furryhook.utils.combat.rotations;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public interface AuraRotation {
    void tick(LocalPlayer player, LivingEntity target, Vec3 targetEyePos, boolean attackLikely);

    default void onAttack() {
    }

    default void reset() {
    }
}
