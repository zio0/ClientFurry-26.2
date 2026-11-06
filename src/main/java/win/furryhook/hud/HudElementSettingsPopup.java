package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import win.furryhook.feature.impl.visual.HudFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.AnimatedValue;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.TextAlign;
import win.furryhook.utils.render.gui.UiFontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Small per-element settings popup opened by right-clicking a HUD element while editing (chat
 * open), styled after the main menu's flat "Fascia" panels: a solid PANEL fill with a hairline
 * border (no glass/blur) anchored near the element with a stack of toggle rows - every element
 * gets "Always show", and the watermark additionally gets one row per content item
 * (Player/FPS/Ping) bound to {@link HudFeature#watermarkContent}.
 */
public final class HudElementSettingsPopup {
    private static final float MIN_WIDTH = 118.0F;
    private static final float PAD_X = 8.0F;
    private static final float TITLE_SIZE = 9.0F;
    private static final float HEADER_HEIGHT = 22.0F;
    private static final float ROW_HEIGHT = 15.0F;
    private static final float ROW_BOTTOM_PAD = 7.0F;
    private static final float LABEL_SIZE = 9.0F;
    private static final float SWITCH_WIDTH = 22.0F;
    private static final float SWITCH_HEIGHT = 12.0F;

    private record Row(String label, BooleanSupplier isOn, Supplier<String> valueText, Runnable action) {
        private Row(String label, BooleanSupplier isOn, Runnable toggle) {
            this(label, isOn, null, toggle);
        }

        private Row(String label, Supplier<String> valueText, Runnable cycle) {
            this(label, null, valueText, cycle);
        }
    }

    private final AnimatedValue visibilityAnim = new AnimatedValue();

    private HudElement target;
    private String title = "";
    private List<Row> rows = List.of();
    private float x;
    private float y;
    private boolean open;
    private float visible;

    public void open(HudElement element, String title, float anchorX, float anchorY, float screenWidth, float screenHeight, float unit) {
        this.target = element;
        this.title = title;
        this.rows = buildRows(element);
        this.open = true;
        float width = width(unit);
        float height = height(unit);
        this.x = Math.max(4.0F, Math.min(anchorX, screenWidth - width - 4.0F));
        this.y = Math.max(4.0F, Math.min(anchorY, screenHeight - height - 4.0F));
    }

    private static List<Row> buildRows(HudElement element) {
        List<Row> list = new ArrayList<>();
        if (element instanceof WatermarkElement) {
            for (String item : WatermarkElement.CONTENT_ITEMS) {
                list.add(new Row(item,
                        () -> HudFeature.watermarkShows(item),
                        () -> HudFeature.toggleWatermarkContent(item)));
            }
        } else {
            list.add(new Row("Always show", element::alwaysShow,
                    () -> element.setAlwaysShow(!element.alwaysShow())));
        }
        return list;
    }

    public void close() {
        this.open = false;
        this.target = null;
    }

    public boolean isOpen() {
        return this.open;
    }

    public boolean contains(float mouseX, float mouseY, float unit) {
        return this.open
                && mouseX >= this.x && mouseX <= this.x + width(unit)
                && mouseY >= this.y && mouseY <= this.y + height(unit);
    }

    public boolean handleClick(float mouseX, float mouseY, float unit) {
        if (!this.open || this.target == null) {
            return false;
        }
        float width = width(unit);
        for (int i = 0; i < this.rows.size(); i++) {
            float rowY = this.y + HEADER_HEIGHT * unit + ROW_HEIGHT * unit * i;
            if (mouseX >= this.x && mouseX <= this.x + width
                    && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT * unit) {
                this.rows.get(i).action().run();
                return true;
            }
        }
        return true;
    }

    public void render(Minecraft mc, float unit) {
        long now = System.nanoTime();
        this.visible = this.visibilityAnim.update(this.open ? 1.0F : 0.0F, 0.16F, now);
        if (this.visible <= 0.01F) {
            return;
        }

        float width = width(unit);
        float height = height(unit);
        float x = this.x;
        float y = this.y;

        Render2DUtil.rect(x, y, width, height)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.PANEL, this.visible))
                .radius(8.0F * unit)
                .border(Math.max(0.5F, 0.5F * unit), ColorUtil.multiplyAlpha(Theme.Colors.PANEL_BORDER, this.visible))
                .draw();

        Render2DUtil.text(x + PAD_X * unit, y + 5.0F * unit, TITLE_SIZE * unit, this.title)
                .style(UiFontStyle.MEDIUM)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, this.visible))
                .draw();

        float dividerW = width * 0.47F;
        Render2DUtil.rect(x + (width - dividerW) * 0.5F, y + 15.0F * unit, dividerW, Math.max(0.5F, 0.5F * unit))
                .color(ColorUtil.multiplyAlpha(HudElement.DIVIDER_COLOR, this.visible * 0.92F))
                .draw();

        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            float rowY = y + HEADER_HEIGHT * unit + ROW_HEIGHT * unit * i;

            Render2DUtil.text(x + PAD_X * unit, rowY + (ROW_HEIGHT - LABEL_SIZE) * 0.5F * unit + 1.0F * unit, LABEL_SIZE * unit, row.label())
                    .style(UiFontStyle.MEDIUM)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TEXT, this.visible))
                    .draw();

            if (row.valueText() != null) {
                Render2DUtil.text(x + width - PAD_X * unit, rowY + (ROW_HEIGHT - LABEL_SIZE) * 0.5F * unit + 1.0F * unit,
                                LABEL_SIZE * unit, row.valueText().get())
                        .style(UiFontStyle.MEDIUM)
                        .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                        .align(TextAlign.RIGHT)
                        .draw();
                continue;
            }

            boolean enabled = row.isOn().getAsBoolean();
            float switchWidth = SWITCH_WIDTH * unit;
            float switchHeight = SWITCH_HEIGHT * unit;
            float switchX = x + width - PAD_X * unit - switchWidth;
            float switchY = rowY + (ROW_HEIGHT - SWITCH_HEIGHT) * 0.5F * unit;
            int trackColor = enabled ? Theme.getAccent() : Theme.Colors.CONTROL_STRONG;
            Render2DUtil.rect(switchX, switchY, switchWidth, switchHeight)
                    .color(ColorUtil.multiplyAlpha(trackColor, this.visible))
                    .radius(switchHeight * 0.5F)
                    .draw();

            float knobSize = switchHeight - 4.0F * unit;
            float knobX = enabled ? switchX + switchWidth - knobSize - 2.0F * unit : switchX + 2.0F * unit;
            float knobY = switchY + 2.0F * unit;
            Render2DUtil.rect(knobX, knobY, knobSize, knobSize)
                    .color(ColorUtil.multiplyAlpha(ColorUtil.WHITE, this.visible))
                    .radius(knobSize * 0.5F)
                    .draw();
        }
    }

    /**
     * Tracks the target HUD element's own on-screen width live (it's already unit-scaled), so the
     * popup grows/shrinks together with it - e.g. the watermark widening as content rows are
     * toggled on. Floored at {@link #MIN_WIDTH} so the row labels/switches always have room.
     */
    private float width(float unit) {
        float targetWidth = this.target != null ? this.target.width() : 0.0F;
        return Math.max(MIN_WIDTH * unit, targetWidth);
    }

    private float height(float unit) {
        int rowCount = this.rows.isEmpty() ? 1 : this.rows.size();
        return (HEADER_HEIGHT + ROW_HEIGHT * rowCount + ROW_BOTTOM_PAD) * unit;
    }
}
