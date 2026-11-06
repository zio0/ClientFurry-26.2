package win.furryhook.feature.impl.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.pve.AutomationResource;
import win.furryhook.pve.PveAutomationCoordinator;
import win.furryhook.utils.combat.SprintManager;

public final class SprintFeature extends Feature {
    public final BooleanSetting omniDirectional = register(new BooleanSetting("Omni Directional", false));
    public final BooleanSetting keepSprint = register(new BooleanSetting("Keep Sprint", true));
    public final BooleanSetting legit = register(new BooleanSetting(
            "Legit Mode", false
    ).visibleWhen(() -> !omniDirectional.getValue()));

    public SprintFeature() {
        super("Sprint", "Automatically sprints while moving", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static SprintFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(SprintFeature.class);
    }

    public static boolean shouldForceSprintKey() {
        return getEnabled() != null && !getEnabled().legit.getValue() && !pveControlsMovement();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (pveControlsMovement()) {
            return;
        }

        LocalPlayer player = event.getClient().player;
        if (player == null || player.isSpectator()) {
            return;
        }

        if (omniDirectional.getValue()) {
            omniDirectionalTick(player);
        } else if (legit.getValue()) {
            legitTick(player);
        }
    }

    private void omniDirectionalTick(LocalPlayer player) {
        if (player.isUsingItem()) {
            return;
        }
        if (SprintManager.shouldFreezeMovementInput()) {
            return;
        }
        if (player.input.getMoveVector().lengthSquared() > 0.0F
                && (keepSprint.getValue() || !player.isSprinting())) {
            player.setSprinting(true);
        }
    }

    /**
     * Ported from nuclear's {@code AutoSprint}: only starts sprinting forward, gated
     * on the same conditions vanilla itself uses before allowing a sprint to start.
     */
    private void legitTick(LocalPlayer player) {
        boolean canStartSprint = player.input.keyPresses.forward()
                && !player.hasEffect(MobEffects.BLINDNESS)
                && !player.isFallFlying()
                && !player.isUsingItem()
                && !player.horizontalCollision
                && player.getFoodData().getFoodLevel() > 6
                && !player.isShiftKeyDown();

        if (canStartSprint && !player.isSprinting()) {
            player.setSprinting(true);
        }
    }

    private static boolean pveControlsMovement() {
        return PveAutomationCoordinator.INSTANCE.isClaimed(AutomationResource.MOVEMENT)
                || PveAutomationCoordinator.INSTANCE.isClaimed(AutomationResource.NAVIGATION);
    }
}
