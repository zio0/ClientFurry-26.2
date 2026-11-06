package win.furryhook.feature.impl.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;

public final class NoJumpBoostFeature extends Feature {
    public NoJumpBoostFeature() {
        super("NoJumpBoost", "Removes jump boost effect", FeatureCategory.PLAYER, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player != null && player.hasEffect(MobEffects.JUMP_BOOST)) {
            player.removeEffect(MobEffects.JUMP_BOOST);
        }
    }
}
