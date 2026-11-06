package win.furryhook.mixin.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import win.furryhook.event.EventManager;
import win.furryhook.event.Events;
import win.furryhook.event.events.screen.ScreenCloseEvent;
import win.furryhook.event.events.screen.ScreenKeyEvent;
import win.furryhook.event.events.screen.ScreenRenderEvent;
import win.furryhook.feature.impl.visual.AnimationsFeature;
import win.furryhook.menu.core.MenuOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Unique
    private static final long furryhook$CONTAINER_ANIMATION_DURATION_NANOS = 220_000_000L;

    @Unique
    private static Screen furryhook$animatedContainerScreen;
    @Unique
    private static long furryhook$containerAnimationStartNanos;

    @Shadow
    @Final
    protected net.minecraft.client.Minecraft minecraft;

    @Unique
    private boolean furryhook$containerTransformApplied;
    @Unique
    private boolean furryhook$backgroundBypassApplied;
    @Unique
    private float furryhook$containerCenterX;
    @Unique
    private float furryhook$containerCenterY;
    @Unique
    private float furryhook$containerScale;
    @Unique
    private float furryhook$containerOffsetY;

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void onClose(CallbackInfo ci) {
        if (!EventManager.hasListeners(ScreenCloseEvent.class)) {
            return;
        }

        if (EventManager.call(Events.SCREEN_CLOSE.set(this.minecraft, (Screen) (Object) this)).isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void onExtractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!EventManager.hasListeners(ScreenRenderEvent.class)) {
            return;
        }

        if (EventManager.call(Events.SCREEN_RENDER.set((Screen) (Object) this, guiGraphicsExtractor, mouseX, mouseY, partialTick)).isCancelled()) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "extractRenderState", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private int maskMouseXWhenOverlayOpen(int mouseX) {
        return MenuOverlay.isOpen() ? Integer.MIN_VALUE / 4 : mouseX;
    }

    @ModifyVariable(method = "extractRenderState", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private int maskMouseYWhenOverlayOpen(int mouseY) {
        return MenuOverlay.isOpen() ? Integer.MIN_VALUE / 4 : mouseY;
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (!EventManager.hasListeners(ScreenKeyEvent.class)) {
            return;
        }

        if (EventManager.call(Events.SCREEN_KEY.set((Screen) (Object) this, keyEvent, ScreenKeyEvent.Action.PRESS)).isCancelled()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void furryhook$markContainerOpened(CallbackInfo ci) {
        if (!AnimationsFeature.usesContainerAnimation()) {
            return;
        }
        if ((Object) this instanceof AbstractContainerScreen<?> handledScreen && handledScreen != furryhook$animatedContainerScreen) {
            furryhook$animatedContainerScreen = handledScreen;
            furryhook$containerAnimationStartNanos = System.nanoTime();
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void furryhook$pushContainerAnimation(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!(((Object) this instanceof AbstractContainerScreen<?> handledScreen) && AnimationsFeature.usesContainerAnimation())) {
            this.furryhook$containerTransformApplied = false;
            return;
        }

        float progress = furryhook$containerAnimationProgress(handledScreen);
        if (progress >= 0.999F) {
            this.furryhook$containerTransformApplied = false;
            return;
        }

        this.furryhook$containerScale = 0.9F + 0.1F * progress;
        this.furryhook$containerOffsetY = (1.0F - progress) * 18.0F;
        this.furryhook$containerCenterX = context.guiWidth() * 0.5F;
        this.furryhook$containerCenterY = context.guiHeight() * 0.5F;

        context.pose().pushMatrix();
        context.pose().translate(this.furryhook$containerCenterX, this.furryhook$containerCenterY + this.furryhook$containerOffsetY);
        context.pose().scale(this.furryhook$containerScale, this.furryhook$containerScale);
        context.pose().translate(-this.furryhook$containerCenterX, -this.furryhook$containerCenterY);
        this.furryhook$containerTransformApplied = true;
        this.furryhook$backgroundBypassApplied = false;
    }

    @Inject(method = "extractBackground", at = @At("HEAD"))
    private void furryhook$disableAnimationForDarkening(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!this.furryhook$containerTransformApplied || !((Object) this instanceof AbstractContainerScreen<?>)) {
            this.furryhook$backgroundBypassApplied = false;
            return;
        }

        context.pose().popMatrix();
        this.furryhook$backgroundBypassApplied = true;
    }

    @Inject(method = "extractBackground", at = @At("RETURN"))
    private void furryhook$restoreAnimationAfterDarkening(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!this.furryhook$backgroundBypassApplied || !((Object) this instanceof AbstractContainerScreen<?>)) {
            return;
        }

        context.pose().pushMatrix();
        context.pose().translate(this.furryhook$containerCenterX, this.furryhook$containerCenterY + this.furryhook$containerOffsetY);
        context.pose().scale(this.furryhook$containerScale, this.furryhook$containerScale);
        context.pose().translate(-this.furryhook$containerCenterX, -this.furryhook$containerCenterY);
        this.furryhook$backgroundBypassApplied = false;
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void furryhook$popContainerAnimation(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.furryhook$backgroundBypassApplied) {
            context.pose().pushMatrix();
            context.pose().translate(this.furryhook$containerCenterX, this.furryhook$containerCenterY + this.furryhook$containerOffsetY);
            context.pose().scale(this.furryhook$containerScale, this.furryhook$containerScale);
            context.pose().translate(-this.furryhook$containerCenterX, -this.furryhook$containerCenterY);
            this.furryhook$backgroundBypassApplied = false;
        }
        if (!this.furryhook$containerTransformApplied) {
            return;
        }
        context.pose().popMatrix();
        this.furryhook$containerTransformApplied = false;
    }

    @Unique
    private static float furryhook$containerAnimationProgress(Screen screen) {
        if (screen != furryhook$animatedContainerScreen) {
            furryhook$animatedContainerScreen = screen;
            furryhook$containerAnimationStartNanos = System.nanoTime();
            return 0.0F;
        }

        if (furryhook$containerAnimationStartNanos == 0L) {
            furryhook$containerAnimationStartNanos = System.nanoTime();
            return 0.0F;
        }

        long elapsed = System.nanoTime() - furryhook$containerAnimationStartNanos;
        float raw = Math.max(0.0F, Math.min(1.0F, elapsed / (float) furryhook$CONTAINER_ANIMATION_DURATION_NANOS));
        return furryhook$easeOutCubic(raw);
    }

    @Unique
    private static float furryhook$easeOutCubic(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        float inv = 1.0F - clamped;
        return 1.0F - inv * inv * inv;
    }
}
