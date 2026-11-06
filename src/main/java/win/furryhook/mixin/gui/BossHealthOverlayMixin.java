package win.furryhook.mixin.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import win.furryhook.feature.impl.visual.RemovalsFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @Unique
    private boolean furryhook$shifted;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void onExtractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, CallbackInfo ci) {
        if (RemovalsFeature.shouldRemoveBossBar()) {
            ci.cancel();
            return;
        }
        float offset = win.furryhook.feature.impl.visual.HudFeature.bossBarYOffset();
        this.furryhook$shifted = offset != 0.0F;
        if (this.furryhook$shifted) {
            guiGraphicsExtractor.pose().pushMatrix();
            guiGraphicsExtractor.pose().translate(0.0F, offset);
        }
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void furryhook$unshift(GuiGraphicsExtractor guiGraphicsExtractor, CallbackInfo ci) {
        if (this.furryhook$shifted) {
            guiGraphicsExtractor.pose().popMatrix();
            this.furryhook$shifted = false;
        }
    }
}
