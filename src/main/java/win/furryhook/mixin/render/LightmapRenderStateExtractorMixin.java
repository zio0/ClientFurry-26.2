package win.furryhook.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.entity.LivingEntity;
import win.furryhook.feature.impl.player.FullBrightFeature;
import win.furryhook.feature.impl.visual.WorldTweaksFeature;
import win.furryhook.utils.ColorUtil;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    @ModifyExpressionValue(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"
            ),
            slice = @Slice(
                    from = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;"
                    ),
                    to = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/Options;darknessEffectScale()Lnet/minecraft/client/OptionInstance;"
                    )
            )
    )
    private Object dynamicFullBrightGamma(Object original) {
        return original instanceof Double gamma
                ? FullBrightFeature.modifyGamma(gamma)
                : original;
    }

    @Inject(method = "calculateDarknessScale", at = @At("HEAD"), cancellable = true)
    private void suppressDarkness(LivingEntity entity,
                                        float factor,
                                        float partialTick,
                                        CallbackInfoReturnable<Float> callback) {
        if (FullBrightFeature.shouldSuppressDarkness()) {
            callback.setReturnValue(0.0F);
        }
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void worldLightTint(LightmapRenderState state,
                                      float partialTick,
                                      CallbackInfo ci) {
        WorldTweaksFeature worldTweaks = WorldTweaksFeature.getEnabled();
        if (worldTweaks == null || !worldTweaks.usesWorldColor()) {
            return;
        }
        int color = worldTweaks.resolvedWorldColor();
        Vector3f tint = new Vector3f(
                ColorUtil.red(color) / 255.0F,
                ColorUtil.green(color) / 255.0F,
                ColorUtil.blue(color) / 255.0F
        );
        state.blockLightTint = new Vector3f(state.blockLightTint).mul(tint);
        state.skyLightColor = new Vector3f(state.skyLightColor).mul(tint);
        state.ambientColor = new Vector3f(state.ambientColor).mul(tint);
    }
}
