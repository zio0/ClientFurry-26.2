package win.furryhook.mixin.render;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import win.furryhook.emote.EmoteEngine;
import win.furryhook.emote.EmotePoseCarrier;
import win.furryhook.feature.impl.visual.NameTagsFeature;
import win.furryhook.utils.render.ClientCape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void forceCapeVisible(Avatar avatar, AvatarRenderState state, float tickDelta, CallbackInfo ci) {
        if (!ClientCape.shouldForceCape(avatar.getUUID())) {
            return;
        }

        state.showCape = true;
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void furryhook$captureEmotePose(Avatar avatar, AvatarRenderState state, float tickDelta, CallbackInfo ci) {
        ((EmotePoseCarrier) state).furryhook$setEmotePose(EmoteEngine.INSTANCE.poseFor(avatar.getUUID()));
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void hideVanillaNameTag(Avatar avatar, double distanceSqr, CallbackInfoReturnable<Boolean> cir) {
        if (NameTagsFeature.shouldHideVanillaTag()) {
            cir.setReturnValue(false);
        }
    }
}
