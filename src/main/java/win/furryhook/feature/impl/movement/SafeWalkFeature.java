package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.input.PlayerInputEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;

public final class SafeWalkFeature extends Feature implements MinecraftContext {
    public SafeWalkFeature() {
        super("SafeWalk", "Prevents walking off block edges", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onInput(PlayerInputEvent event) {
        LocalPlayer player = player();
        if (player == null || level() == null || !player.onGround()) {
            return;
        }

        BlockPos below = player.blockPosition().below();
        if (level().getBlockState(below).getCollisionShape(level(), below).isEmpty()) {
            event.setShift(true);
        }
    }
}
