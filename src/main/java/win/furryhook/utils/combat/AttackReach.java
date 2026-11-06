package win.furryhook.utils.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.component.AttackRange;

public final class AttackReach {
    private AttackReach() {
    }

    public static AttackRange range(LocalPlayer player) {
        return player.getAttackRangeWith(player.getWeaponItem());
    }

    public static double max(LocalPlayer player) {
        return range(player).effectiveMaxRange(player);
    }

    public static double min(LocalPlayer player) {
        return range(player).effectiveMinRange(player);
    }

    public static double margin(LocalPlayer player) {
        return range(player).hitboxMargin();
    }
}
