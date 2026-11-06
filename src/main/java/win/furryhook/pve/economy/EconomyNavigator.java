package win.furryhook.pve.economy;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import win.furryhook.feature.impl.pve.PveManagerFeature;
import win.furryhook.pve.navigation.NavigationOptions;
import win.furryhook.pve.navigation.Navigator;

import java.util.Objects;

public final class EconomyNavigator {
    private final Navigator navigator;
    private BlockPos goal;
    private boolean begun;

    public EconomyNavigator(Navigator navigator) {
        this.navigator = Objects.requireNonNull(navigator, "navigator");
    }

    public boolean moveTo(LocalPlayer player, BlockPos target, int radius) {
        if (player == null || target == null) {
            return false;
        }
        if (arrived(player, target, Math.max(1.5, radius + 0.75))) {
            cancel();
            return true;
        }
        if (!this.navigator.isAvailable()) {
            return false;
        }
        if (!this.begun) {
            this.navigator.begin(PveManagerFeature.INSTANCE.configureNavigation(
                    NavigationOptions.walking()
            ));
            this.begun = true;
        }
        BlockPos immutable = target.immutable();
        if (!immutable.equals(this.goal) || !this.navigator.isPathing()) {
            this.goal = immutable;
            this.navigator.pathTo(immutable, Math.max(1, radius));
        }
        return false;
    }

    public void cancel() {
        if (this.begun) {
            this.navigator.cancel();
        }
        this.goal = null;
    }

    public void close() {
        if (this.begun) {
            this.navigator.end();
        }
        this.begun = false;
        this.goal = null;
    }

    public static boolean arrived(LocalPlayer player, BlockPos target, double distance) {
        return player != null
                && target != null
                && target.distToCenterSqr(player.getX(), player.getY(), player.getZ())
                <= distance * distance;
    }
}
