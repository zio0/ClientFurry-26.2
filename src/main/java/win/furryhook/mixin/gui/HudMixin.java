package win.furryhook.mixin.gui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.scores.Objective;
import win.furryhook.context.RenderContext;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.visual.CrosshairFeature;
import win.furryhook.feature.impl.visual.RemovalsFeature;
import win.furryhook.event.EventManager;
import win.furryhook.event.Events;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.utils.render.gui.Render2DUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void onDisplayScoreboardSidebar(GuiGraphicsExtractor guiGraphicsExtractor, Objective objective, CallbackInfo ci) {
        if (RemovalsFeature.shouldRemoveScoreboard()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void hideVanillaCrosshair(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (FeatureManager.INSTANCE.getEnabled(CrosshairFeature.class) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void furryhook$hideVanillaEffectsOverlay(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (RemovalsFeature.shouldRemoveEffectsOverlay()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        RenderContext.enter2D(null, guiGraphicsExtractor, deltaTracker);
        try {
            Render2DUtil.beginFrame();

            if (EventManager.hasListeners(Render2DEvent.class)) {
                EventManager.call(Events.RENDER_2D.set(this.minecraft, null, guiGraphicsExtractor, deltaTracker));
            }

            Render2DUtil.flush();
        } finally {
            RenderContext.exit2D();
        }
    }
}
