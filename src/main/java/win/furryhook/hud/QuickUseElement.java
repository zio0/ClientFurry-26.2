package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import win.furryhook.context.RenderContext;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.impl.misc.ServerHelperFeature;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.TextAlign;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;
import org.joml.Matrix3x2fStack;

import java.util.List;

public final class QuickUseElement extends HudElement {
    private static final float LOCAL_SCALE = 0.85F;
    private static final int MAX_COLUMNS = 6;
    private static final float PADDING = 5.0F;
    private static final float CELL_SIZE = 31.0F;
    private static final float CELL_GAP = 2.5F;
    private static final float ITEM_SIZE = 20.0F;
    private static final float PANEL_RADIUS = 8.0F;
    private static final float CELL_RADIUS = 4.0F;
    private static final float KEY_TEXT_SIZE = 7.5F;
    private static final float COUNT_TEXT_SIZE = 8.0F;

    private List<Object> entries = List.of(); // Temporary: QuickUseEntry removed
    private int columns;

    public QuickUseElement() {
        super("quick_use", "QuickUse");
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        unit *= LOCAL_SCALE;
        ServerHelperFeature helper = FeatureManager.INSTANCE.getEnabled(ServerHelperFeature.class);
        LocalPlayer player = mc.player;
        // Temporary disabled - QuickUseEntry removed
        this.entries = List.of(); // helper == null || player == null ? List.of() : helper.quickUseEntries(player);
        if (this.entries.isEmpty()) {
            this.columns = 0;
            this.width = 0.0F;
            this.height = 0.0F;
            return;
        }

        this.columns = Math.min(MAX_COLUMNS, this.entries.size());
        int rows = (this.entries.size() + this.columns - 1) / this.columns;
        this.width = (PADDING * 2.0F + this.columns * CELL_SIZE
                + Math.max(0, this.columns - 1) * CELL_GAP) * unit;
        this.height = (PADDING * 2.0F + rows * CELL_SIZE
                + Math.max(0, rows - 1) * CELL_GAP) * unit;
    }

    @Override
    protected float defaultY(float unit) {
        return 220.0F * unit;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        unit *= LOCAL_SCALE;
        if (this.entries.isEmpty() || this.columns <= 0 || mc.player == null) {
            return;
        }

        float alpha = appearAlpha();
        drawPanelBacking(unit, PANEL_RADIUS * unit, alpha);
        Render2DUtil.rect(this.x, this.y, this.width, this.height)
                .flatPanel(alpha, 0.5F * unit)
                .radius(PANEL_RADIUS * unit)
                .draw();
        drawShine(unit);

        float cellSize = CELL_SIZE * unit;
        float cellGap = CELL_GAP * unit;
        float cellsX = this.x + PADDING * unit;
        float cellsY = this.y + PADDING * unit;
        for (int index = 0; index < this.entries.size(); index++) {
            int column = index % this.columns;
            int row = index / this.columns;
            float cellX = cellsX + column * (cellSize + cellGap);
            float cellY = cellsY + row * (cellSize + cellGap);
            Render2DUtil.rect(cellX, cellY, cellSize, cellSize)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_SMALL, alpha))
                    .radius(CELL_RADIUS * unit)
                    .draw();
        }

        if (alpha < 0.75F) {
            return;
        }
        GuiGraphicsExtractor extractor = RenderContext.currentGuiGraphicsExtractor();
        if (extractor == null) {
            return;
        }

        Render2DUtil.flush();
        Matrix3x2fStack pose = extractor.pose();
        float guiScale = (float) mc.getWindow().getGuiScale();
        float itemSize = ITEM_SIZE * unit;
        float itemScale = itemSize / 16.0F;
        MsdfFont keyFont = UiFonts.sfPro(UiFontStyle.SEMIBOLD.weight());
        MsdfFont countFont = UiFonts.sfPro(UiFontStyle.SEMIBOLD.weight());
        float keySize = KEY_TEXT_SIZE * unit;
        float countSize = COUNT_TEXT_SIZE * unit;
        float keySpacing = keySize * UiFontStyle.SEMIBOLD.letterSpacingEm();

        for (int index = 0; index < this.entries.size(); index++) {
            // Temporary disabled - QuickUseEntry removed
            /*
            ServerHelperFeature.QuickUseEntry entry = this.entries.get(index);
            int column = index % this.columns;
            int row = index / this.columns;
            float cellX = cellsX + column * (cellSize + cellGap);
            float cellY = cellsY + row * (cellSize + cellGap);
            ItemStack icon = entry.icon();

            if (!icon.isEmpty()) {
                float itemX = cellX + (cellSize - itemSize) / 2.0F;
                float itemY = cellY + (cellSize - itemSize) / 2.0F;
                itemX = Math.round(itemX * guiScale) / guiScale;
                itemY = Math.round(itemY * guiScale) / guiScale;

                pose.pushMatrix();
                pose.translate(itemX, itemY);
                pose.scale(itemScale);
                extractor.item(mc.player, icon, 0, 0, index + 1);
                extractor.itemDecorations(mc.font, icon, 0, 0, "");
                pose.popMatrix();
            }

            String bind = compactBind(entry.bindLabel());
            bind = keyFont.ellipsize(bind, keySize, keySpacing, cellSize - 6.0F * unit);
            Render2DUtil.text(cellX + 3.0F * unit, cellY + 2.0F * unit, keySize, bind)
                    .style(UiFontStyle.SEMIBOLD)
                    .color(Theme.Colors.TEXT_TITLE)
                    .draw();

            if (entry.count() != 1) {
                String count = Integer.toString(entry.count());
                float countRight = cellX + cellSize - 2.0F * unit;
                float countBottom = cellY + cellSize - 2.0F * unit;
                Render2DUtil.text(
                                countRight,
                                countBottom - countFont.textHeight(countSize),
                                countSize,
                                count
                        )
                        .style(UiFontStyle.SEMIBOLD)
                        .color(entry.count() > 0 ? Theme.Colors.TEXT_TITLE : Theme.Colors.TEXT_GHOST)
                        .align(TextAlign.RIGHT)
                        .draw();
            }
            */
        }
    }

    private static String compactBind(String label) {
        return switch (label) {
            case "Mouse Left" -> "M1";
            case "Mouse Right" -> "M2";
            case "Mouse Middle" -> "M3";
            default -> label != null && label.startsWith("Mouse ")
                    ? "M" + label.substring("Mouse ".length())
                    : label;
        };
    }
}
