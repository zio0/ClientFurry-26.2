package win.furryhook.menu.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import win.furryhook.context.RenderContext;
import win.furryhook.utils.render.gui.Render2DUtil;

/**
 * Opens a 2D render context around a screen draw so {@link Render2DUtil} can be used
 * straight from a vanilla screen the way it is used from the HUD overlay handler.
 *
 * Nested calls are tolerated: if something up the stack already entered 2D, the queue
 * is left alone and only flushed, never re-begun.
 */
public final class UiFrame {
    private UiFrame() {
    }

    public static void draw(GuiGraphicsExtractor extractor, Runnable body) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean nested = RenderContext.isIn2D();
        if (!nested) {
            RenderContext.enter2D(minecraft.gui, extractor, minecraft.getDeltaTracker());
        }
        try {
            if (!nested) {
                Render2DUtil.beginFrame();
            }
            body.run();
            Render2DUtil.flush();
        } finally {
            if (!nested) {
                RenderContext.exit2D();
            }
        }
    }
}
