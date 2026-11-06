package win.furryhook.mixin.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventManager;
import win.furryhook.event.Events;
import win.furryhook.event.events.game.ElytraPostTravelEvent;
import win.furryhook.event.events.game.PlayerJumpEvent;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.visual.RemovalsFeature;
import win.furryhook.feature.impl.visual.SwingAnimationFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void onPlayerJump(CallbackInfo ci) {
        LocalPlayer player = MinecraftContext.mc.player;
        if (player != null && (Object) this == player && EventManager.hasListeners(PlayerJumpEvent.class)) {
            EventManager.call(Events.PLAYER_JUMP.set(player, player.position()));
        }
    }

    @Inject(method = "updateFallFlyingMovement", at = @At("RETURN"), cancellable = true)
    private void onElytraPostTravel(Vec3 oldVelocity, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this != MinecraftContext.mc.player || !EventManager.hasListeners(ElytraPostTravelEvent.class)) {
            return;
        }
        ElytraPostTravelEvent event = Events.ELYTRA_POST_TRAVEL.set(oldVelocity.multiply(0.9900000095367432D, 0.9800000190734863D, 0.9900000095367432D));
        EventManager.call(event);
        cir.setReturnValue(event.getOldVelocity());
    }

    @Inject(method = "getEffectBlendFactor", at = @At("HEAD"), cancellable = true)
    private void onGetEffectBlendFactor(Holder<MobEffect> effect, float partialTick, CallbackInfoReturnable<Float> cir) {
        if ((Object) this == MinecraftContext.mc.player && RemovalsFeature.shouldRemoveBadEffectsVisuals()) {
            cir.setReturnValue(0.0F);
        }
    }

    @Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true)
    private void customSwingDuration(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != MinecraftContext.mc.player) {
            return;
        }
        SwingAnimationFeature swing = FeatureManager.INSTANCE.getEnabled(SwingAnimationFeature.class);
        if (swing == null || !swing.shouldModifyDuration()) {
            return;
        }

        LivingEntity entity = (LivingEntity) (Object) this;
        float duration = swing.durationMultiplier();
        if (MobEffectUtil.hasDigSpeed(entity)) {
            duration *= 6 - (1 + MobEffectUtil.getDigSpeedAmplification(entity));
        } else if (entity.hasEffect(MobEffects.MINING_FATIGUE)) {
            duration *= 6 + (1 + entity.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) * 2;
        } else {
            duration *= 6;
        }
        cir.setReturnValue(Math.max(1, (int) duration));
    }
}
