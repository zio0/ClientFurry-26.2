package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.NumberSetting;

public final class GrimFlyFeature extends Feature {
    public final NumberSetting speed = register(new NumberSetting("Speed", 0.18, 0.05, 0.3, 0.01, ""));
    public final NumberSetting verticalSpeed = register(new NumberSetting("Vertical Speed", 0.08, 0.02, 0.15, 0.01, ""));

    public GrimFlyFeature() {
        super("GrimFly", "Low-speed flight", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null || player.isSpectator() || player.getAbilities().flying || player.isFallFlying()) {
            return;
        }

        Vec2 input = player.input.getMoveVector();
        player.setDeltaMovement(Vec3.ZERO);
        if (input.lengthSquared() > 0.0F) {
            player.moveRelative(speed.getValue().floatValue(), new Vec3(input.x, 0.0, input.y));
        }

        double vertical = event.getClient().options.keyJump.isDown() ? verticalSpeed.getValue()
                : event.getClient().options.keyShift.isDown() ? -verticalSpeed.getValue() : 0.0;
        Vec3 movement = player.getDeltaMovement();
        player.setDeltaMovement(movement.x, vertical, movement.z);
    }
}
