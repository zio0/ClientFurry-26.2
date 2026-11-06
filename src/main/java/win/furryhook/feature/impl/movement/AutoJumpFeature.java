package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;

public final class AutoJumpFeature extends Feature {
    public final BooleanSetting aura = register(new BooleanSetting("Aura", true));
    public final BooleanSetting negativeEffects = register(new BooleanSetting("Negative Effects", true));

    public AutoJumpFeature() {
        super("AutoJump", "Automatically jumps from ground", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalPlayer player = event.getClient().player;
        if (player == null || event.getClient().level == null) {
            return;
        }
        if (PveAutomationCoordinator.INSTANCE.isClaimed(AutomationResource.MOVEMENT)
                || PveAutomationCoordinator.INSTANCE.isClaimed(AutomationResource.NAVIGATION)) {
            return;
        }
        if (!player.onGround() || event.getClient().options.keyJump.isDown()) {
            return;
        }
        if (player.getAbilities().flying || player.isFallFlying()) {
            return;
        }
        if (player.isInWater() || player.isInLava() || player.onClimbable()) {
            return;
        }
        if (!shouldJumpForAura(player) && !shouldJumpForNegativeEffects(player)) {
            return;
        }

        player.jumpFromGround();
    }

    private boolean shouldJumpForAura(LocalPlayer player) {
        if (!aura.getValue()) {
            return false;
        }
        AuraFeature aura = FeatureManager.INSTANCE.getFeature(AuraFeature.class);
        return aura != null && aura.shouldAutoJump(player);
    }

    private boolean shouldJumpForNegativeEffects(LocalPlayer player) {
        if (!negativeEffects.getValue()) {
            return false;
        }
        return player.input.getMoveVector().lengthSquared() > 0.0F && hasNegativeEffects(player);
    }

    private boolean hasNegativeEffects(LocalPlayer player) {
        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }
}
