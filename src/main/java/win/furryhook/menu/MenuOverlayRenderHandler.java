package win.furryhook.menu;

import net.minecraft.client.Minecraft;
import win.furryhook.context.RenderContext;
import win.furryhook.emote.EmoteWheel;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.render.FinalGuiRenderEvent;
import win.furryhook.menu.core.MenuOverlay;
import win.furryhook.utils.render.gui.Render2DUtil;

public final class MenuOverlayRenderHandler {
    @EventTarget
    public void onFinalGuiRender(FinalGuiRenderEvent event) {
        Minecraft minecraft = event.getClient();
        RenderContext.enter2D(event.getGui(), event.getGuiGraphicsExtractor(), event.getDeltaTracker());
        try {
            int screenWidth = minecraft.getWindow().getGuiScaledWidth();
            int screenHeight = minecraft.getWindow().getGuiScaledHeight();
            Render2DUtil.beginFrame();
            MenuOverlay.render(minecraft, event.getGuiGraphicsExtractor(), screenWidth, screenHeight);
            EmoteWheel.render(minecraft, event.getGuiGraphicsExtractor(), screenWidth, screenHeight);
            Render2DUtil.flush();
        } finally {
            RenderContext.exit2D();
        }
    }
}
