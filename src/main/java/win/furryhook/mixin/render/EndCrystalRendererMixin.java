package win.furryhook.mixin.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import win.furryhook.feature.impl.visual.CrystalOptimizerFeature;

/**
 * Отсекает кристалл до того, как для него собирается состояние отрисовки — самая ранняя точка,
 * где это стоит ноль.
 */
@Mixin(EndCrystalRenderer.class)
public abstract class EndCrystalRendererMixin {

    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void furryhook$optimize(EndCrystal crystal, Frustum frustum, double x, double y, double z,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (!CrystalOptimizerFeature.shouldRender(crystal)) {
            cir.setReturnValue(false);
        }
    }
}
