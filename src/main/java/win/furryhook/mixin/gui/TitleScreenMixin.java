package win.furryhook.mixin.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import win.furryhook.menu.screen.MainMenuScreen;
import win.furryhook.menu.screen.UiFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hijacks the vanilla title screen: the panorama, logo and button widgets are dropped
 * and {@link MainMenuScreen} draws in their place. The screen instance stays a real
 * TitleScreen so everything that checks for one keeps working.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void furryhook$dropVanillaLayout(CallbackInfo ci) {
        this.clearWidgets();
        MainMenuScreen.onOpen();
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void furryhook$skipPanorama(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY,
                                        float partialTick, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void furryhook$renderMainMenu(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        // Mods that add their title-screen widgets after init() would otherwise survive the
        // clear and sit here invisible but still clickable.
        if (!this.children().isEmpty()) {
            this.clearWidgets();
        }

        Minecraft minecraft = Minecraft.getInstance();
        UiFrame.draw(guiGraphicsExtractor,
                () -> MainMenuScreen.render(minecraft, this.width, this.height, mouseX, mouseY));
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void furryhook$handleClick(MouseButtonEvent mouseButtonEvent, boolean doubleClick,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (MainMenuScreen.mouseClicked(this, Minecraft.getInstance(),
                mouseButtonEvent.x(), mouseButtonEvent.y(), mouseButtonEvent.button())) {
            cir.setReturnValue(true);
        }
    }
}
