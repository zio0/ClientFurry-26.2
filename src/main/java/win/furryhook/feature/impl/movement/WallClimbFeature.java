package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class WallClimbFeature extends Feature {
    public final NumberSetting speed = register(new NumberSetting("Speed", 0.6, 0.1, 1.0, 0.05, ""));

    public WallClimbFeature() {
        super("WallClimb", "Climb walls like a ladder", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null || player.isSpectator() || !player.horizontalCollision) {
            return;
        }

        Vec3 movement = player.getDeltaMovement();
        player.setDeltaMovement(movement.x, speed.getValue(), movement.z);
    }
}
