package win.furryhook.utils.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.PiercingWeapon;
import win.furryhook.context.PlayerContext;

public class AttackWindow implements PlayerContext {

    public static void attack(Entity target) {
        if (mc.gameMode != null && mc.player != null) {
            PiercingWeapon piercing = mc.player.getWeaponItem().get(DataComponents.PIERCING_WEAPON);
            if (piercing != null) {
                mc.gameMode.piercingAttack(piercing);
            } else {
                mc.gameMode.attack(mc.player, target);
            }
            mc.player.swing(InteractionHand.MAIN_HAND);

            SprintManager.onAttack();
        }
    }
}
