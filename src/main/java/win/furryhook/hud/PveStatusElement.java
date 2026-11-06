package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.pve.AutoMineFeature;
import win.furryhook.feature.impl.pve.PveManagerFeature;
import win.furryhook.pve.PveFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PveStatusElement extends HudElement {
    private static final float PADDING = 8.0F;
    private static final float ICON_SIZE = 14.0F;
    private static final float TITLE_SIZE = 11.0F;
    private static final float ROW_SIZE = 9.0F;
    private static final float ROW_HEIGHT = 13.0F;
    private static final float MIN_WIDTH = 168.0F;

    private record Row(String label, String value, boolean accent) {
    }

    private final List<Row> rows = new ArrayList<>();

    public PveStatusElement() {
        super("pve_status", "PvE State");
    }

    @Override
    protected float defaultY(float unit) {
        return 170.0F * unit;
    }

    @Override
    protected boolean preservePositionOnContentResize() {
        return true;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        rebuildRows();
        MsdfFont font = UiFonts.sfProDisplay();
        float rowSize = ROW_SIZE * unit;
        float spacing = rowSize * UiFontStyle.MEDIUM.letterSpacingEm();
        float contentWidth = MIN_WIDTH * unit;
        for (Row row : this.rows) {
            float measured = font.measureWidth(
                    row.label() + "  " + row.value(),
                    rowSize,
                    spacing
            );
            contentWidth = Math.max(contentWidth, measured + PADDING * 2.0F * unit);
        }
        this.width = contentWidth;
        this.height = (PADDING * 2.0F + 18.0F
                + this.rows.size() * ROW_HEIGHT) * unit;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        float alpha = appearAlpha();
        drawPanelBacking(unit, 8.0F * unit, alpha);
        Render2DUtil.rect(this.x, this.y, this.width, this.height)
                .flatPanel(alpha, 0.5F * unit)
                .radius(8.0F * unit)
                .draw();
        drawShine(unit);

        MsdfFont font = UiFonts.sfProDisplay();
        float iconSize = ICON_SIZE * unit;
        float titleSize = TITLE_SIZE * unit;
        float headerCenterY = this.y + (PADDING + 7.0F) * unit;
        Render2DUtil.texture(
                        this.x + PADDING * unit,
                        headerCenterY - iconSize / 2.0F,
                        iconSize,
                        iconSize,
                        Textures.Icons.OPTION
                )
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), alpha))
                .draw();
        Render2DUtil.text(
                        this.x + (PADDING + ICON_SIZE + 6.0F) * unit,
                        font.centeredTextY(headerCenterY, titleSize),
                        titleSize,
                        "PvE State"
                )
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, alpha))
                .draw();

        float cursorY = this.y + (PADDING + 18.0F) * unit;
        float rowSize = ROW_SIZE * unit;
        for (Row row : this.rows) {
            float centerY = cursorY + ROW_HEIGHT * unit / 2.0F;
            Render2DUtil.text(
                            this.x + PADDING * unit,
                            font.centeredTextY(centerY, rowSize),
                            rowSize,
                            row.label()
                    )
                    .style(UiFontStyle.MEDIUM)
                    .color(ColorUtil.multiplyAlpha(
                            row.accent() ? Theme.getAccent() : Theme.Colors.TEXT_TEXT,
                            alpha
                    ))
                    .draw();
            float valueWidth = font.measureWidth(
                    row.value(),
                    rowSize,
                    rowSize * UiFontStyle.MEDIUM.letterSpacingEm()
            );
            Render2DUtil.text(
                            this.x + this.width - PADDING * unit - valueWidth,
                            font.centeredTextY(centerY, rowSize),
                            rowSize,
                            row.value()
                    )
                    .style(UiFontStyle.MEDIUM)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.SECONDARY_DARK, alpha))
                    .draw();
            cursorY += ROW_HEIGHT * unit;
        }
    }

    private void rebuildRows() {
        this.rows.clear();
        AutoMineFeature autoMine =
                FeatureManager.INSTANCE.getFeature(AutoMineFeature.class);
        if (autoMine == null) {
            this.rows.add(new Row("AutoMine", "In development", false));
        } else {
            AutoMineFeature.Status status = autoMine.currentStatus();
            this.rows.add(new Row(
                    "AutoMine",
                    status.active()
                            ? displayPhase(status.phase())
                            : "Disabled",
                    status.active()
            ));
            this.rows.add(new Row(
                    "Mined ores",
                    Integer.toString(status.minedOres()),
                    false
            ));
            this.rows.add(new Row(
                    "Known targets",
                    Integer.toString(status.knownTargets()),
                    false
            ));
            this.rows.add(new Row(
                    "Elapsed",
                    formatDuration(status.elapsedMillis()),
                    false
            ));
            this.rows.add(new Row(
                    "Mine profile",
                    status.profile(),
                    false
            ));
            this.rows.add(new Row(
                    "Tool profile",
                    status.toolProfile(),
                    false
            ));
        }

        for (Feature feature : FeatureManager.INSTANCE.getFeatures()) {
            if (!(feature instanceof PveFeature)
                    || feature == autoMine
                    || feature == PveManagerFeature.INSTANCE
                    || !feature.isEnabled()) {
                continue;
            }
            this.rows.add(new Row(
                    feature.getName(),
                    "In development",
                    false
            ));
        }
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1_000L);
        long hours = totalSeconds / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(
                    Locale.ROOT,
                    "%02d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
            );
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static String displayPhase(String phase) {
        if (phase != null && phase.startsWith("Paused:")) {
            String reason = phase.substring("Paused:".length()).trim();
            return "Paused" + ": " + reason;
        }
        return phase;
    }
}
