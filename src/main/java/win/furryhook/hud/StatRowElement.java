package win.furryhook.hud;

import net.minecraft.resources.Identifier;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.List;

/**
 * Shared "pill row" rendering for the watermark family ({@link WatermarkElement},
 * {@link BottomStatsElement}, {@link TpsElement}): a rounded panel holding icon+text segments
 * separated by hairline dividers, all glass-panel/shine styled the same way.
 */
public abstract class StatRowElement extends HudElement {
    static final float ROW_HEIGHT = 15.5F;
    static final float PAD_X = 5.0F;
    static final float TEXT_SIZE = 7.0F;
    static final float HEAD_SIZE = 9.0F;
    static final float RADIUS = 5.0F;
    static final float ICON_SIZE = 6.5F;
    static final float ICON_GAP = 2.2F;
    static final float DIVIDER_GAP = 3.2F;
    static final float DSF_CROP_V0 = 0.0943F;
    static final float DSF_CROP_V1 = 0.9057F;

    record TextPart(String text, boolean secondary) {
    }

    record Segment(Identifier icon, boolean isPlayerHead, List<TextPart> parts) {
    }

    protected StatRowElement(String id, String displayName) {
        super(id, displayName);
    }

    /** Draws the accent bar on the right edge instead of the left. */
    protected boolean accentBarOnRight() {
        return false;
    }

    static Segment unit(Identifier icon, String value, String suffix) {
        return new Segment(icon, false, List.of(new TextPart(value, false), new TextPart(suffix, true)));
    }

    static Segment coordinates(int x, int y, int z) {
        return new Segment(Textures.Hud.ICON_TARGET, false, List.of(
                new TextPart("x", true), new TextPart(Integer.toString(x), false),
                new TextPart(" y", true), new TextPart(Integer.toString(y), false),
                new TextPart(" z", true), new TextPart(Integer.toString(z), false)
        ));
    }

    static float measureRow(MsdfFont font, List<Segment> segments, float textSize, float unit) {
        float width = PAD_X * 2.0F * unit;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0) {
                width += (DIVIDER_GAP * 2.0F + 1.0F) * unit;
            }
            width += (segment.isPlayerHead() ? HEAD_SIZE : ICON_SIZE) * unit + ICON_GAP * unit;
            for (TextPart part : segment.parts()) {
                width += font.measureWidth(part.text(), textSize, 0.0F);
            }
        }
        return width;
    }

    final void drawRow(List<Segment> segments, float x, float y, float width, float rowHeight, float unit, float alpha) {
        drawPanelBacking(x, y, width, rowHeight, RADIUS * unit, alpha);
        Render2DUtil.rect(x, y, width, rowHeight)
                .flatPanel(alpha, 0.5F * unit)
                .radius(RADIUS * unit)
                .draw();

        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        float textSize = TEXT_SIZE * unit;
        float centerY = y + rowHeight / 2.0F;

        // Small accent bar, matching the rest of the HUD. Sits on the left by default; elements
        // pinned to the screen's right edge (e.g. TPS) flip it to the right via accentBarOnRight().
        float barWidth = 1.3F * unit;
        float barHeight = rowHeight * 0.5F;
        boolean barRight = accentBarOnRight();
        float barX = barRight ? x + width - barWidth : x;
        float barRadius = barWidth * 0.5F;
        Render2DUtil.rect(barX, centerY - barHeight / 2.0F, barWidth, barHeight)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), alpha))
                .radius(barRight ? barRadius : 0.0F, barRight ? 0.0F : barRadius,
                        barRight ? 0.0F : barRadius, barRight ? barRadius : 0.0F)
                .draw();

        float cursor = x + PAD_X * unit;

        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0) {
                cursor += DIVIDER_GAP * unit;
                Render2DUtil.rect(cursor, y + rowHeight * 0.24F, Math.max(0.5F, 0.65F * unit), rowHeight * 0.52F)
                        .color(ColorUtil.multiplyAlpha(DIVIDER_COLOR, alpha))
                        .radius(0.33F * unit)
                        .draw();
                cursor += 0.65F * unit + DIVIDER_GAP * unit;
            }

            if (segment.isPlayerHead()) {
                float headSize = HEAD_SIZE * unit;
                Render2DUtil.texture(cursor, centerY - headSize / 2.0F, headSize, headSize, Textures.Hud.ICON_DSF)
                        .uv(0.0F, DSF_CROP_V0, 1.0F, DSF_CROP_V1)
                        .radius(headSize / 2.0F)
                        .color(ColorUtil.multiplyAlpha(ColorUtil.WHITE, alpha))
                        .draw();
                cursor += headSize + ICON_GAP * unit;
            } else if (segment.icon() != null) {
                float iconSize = ICON_SIZE * unit;
                Render2DUtil.texture(cursor, centerY - iconSize / 2.0F, iconSize, iconSize, segment.icon())
                        .color(ColorUtil.multiplyAlpha(Theme.getAccent(), alpha))
                        .draw();
                cursor += iconSize + ICON_GAP * unit;
            }

            for (TextPart part : segment.parts()) {
                int color = ColorUtil.multiplyAlpha(
                        part.secondary() ? Theme.Colors.TEXT_GHOST : Theme.Colors.PRIMARY_BRIGHT, alpha);
                Render2DUtil.text(cursor, font.centeredTextY(centerY, textSize), textSize, part.text())
                        .style(UiFontStyle.MEDIUM)
                        .color(color)
                        .draw();
                cursor += font.measureWidth(part.text(), textSize, 0.0F);
            }
        }
    }
}
