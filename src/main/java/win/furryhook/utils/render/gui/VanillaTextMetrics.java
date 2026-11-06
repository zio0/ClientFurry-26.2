package win.furryhook.utils.render.gui;

import win.furryhook.context.MinecraftContext;

/**
 * Width/height metrics for vanilla Minecraft font text drawn through
 * {@link Render2DUtil.TextBuilder#vanilla()}, matching the scale math used there
 * (text is scaled from the vanilla font's fixed pixel metrics up to the requested size).
 */
public final class VanillaTextMetrics {
    private VanillaTextMetrics() {
    }

    public static float width(String text, float size) {
        net.minecraft.client.gui.Font font = MinecraftContext.mc.font;
        float rawScale = size / (float) font.lineHeight;
        float scale = Math.max(1.0F, Math.round(rawScale * 4.0F) / 4.0F);
        return font.width(text) * scale;
    }

    public static float lineHeight(float size) {
        net.minecraft.client.gui.Font font = MinecraftContext.mc.font;
        float rawScale = size / (float) font.lineHeight;
        float scale = Math.max(1.0F, Math.round(rawScale * 4.0F) / 4.0F);
        return font.lineHeight * scale;
    }

    public static float centeredTextY(float centerY, float size) {
        return centerY - lineHeight(size) * 0.5F;
    }
}
