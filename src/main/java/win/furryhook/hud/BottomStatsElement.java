package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import win.furryhook.feature.impl.visual.HudFeature;
import win.furryhook.utils.math.AnimatedValue;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fixed bottom-left corner element: coordinates + BPS. Not draggable - rises above the chat input
 * box while it's open instead, then settles back down once it closes.
 */
public final class BottomStatsElement extends StatRowElement {
    public static final String ITEM_COORDINATES = "Coordinates";
    public static final String ITEM_BPS = "BPS";

    private static final float MARGIN = 12.0F;
    private static final float CHAT_LIFT = 64.0F;

    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue bpsAnim = new AnimatedValue();
    private final AnimatedValue liftAnim = new AnimatedValue();
    private final List<Segment> row = new ArrayList<>(2);

    public BottomStatsElement() {
        super("bottom_stats", "Coordinates");
    }

    @Override
    protected boolean customPosition() {
        return true;
    }

    @Override
    protected void applyCustomPosition(Minecraft mc, float unit) {
        boolean chatOpen = mc.gui.screen() instanceof ChatScreen;
        float lift = this.liftAnim.update(chatOpen ? CHAT_LIFT * unit : 0.0F, 0.18F, System.nanoTime());
        this.x = MARGIN * unit;
        this.y = mc.getWindow().getGuiScaledHeight() - this.height - MARGIN * unit - lift;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        double rawBps = mc.player != null ? mc.player.getDeltaMovement().horizontalDistance() * 20.0 : 0.0;
        float bps = this.bpsAnim.update((float) rawBps, 0.08F, now);

        this.row.clear();
        if (HudFeature.informationShows(ITEM_COORDINATES) && mc.player != null) {
            this.row.add(coordinates(
                    (int) Math.floor(mc.player.getX()),
                    (int) Math.floor(mc.player.getY()),
                    (int) Math.floor(mc.player.getZ())));
        }
        if (HudFeature.informationShows(ITEM_BPS)) {
            this.row.add(unit(Textures.Hud.ICON_MOVEMENT, String.format(Locale.ROOT, "%.1f", bps), " bps"));
        }

        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        float textSize = TEXT_SIZE * unit;
        float target = this.row.isEmpty() ? 0.0F : measureRow(font, this.row, textSize, unit);
        float width = this.widthAnim.update(target, 0.13F, now);

        this.width = width;
        this.height = width > 0.5F ? ROW_HEIGHT * unit : 0.0F;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        if (this.width <= 0.5F) {
            return;
        }
        float alpha = appearAlpha();
        float rowHeight = ROW_HEIGHT * unit;
        drawRow(this.row, this.x, this.y, this.width, rowHeight, unit, alpha);
        drawShine(this.x, this.y, this.width, rowHeight, unit);
    }
}
