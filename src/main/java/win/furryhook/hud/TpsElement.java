package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import win.furryhook.event.EventManager;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.GameTickEvent;
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
 * Fixed bottom-right corner element: TPS. Not draggable - rises above the chat input box while
 * it's open instead, then settles back down once it closes.
 */
public final class TpsElement extends StatRowElement {
    public static final String ITEM_TPS = "TPS";

    private static final float MARGIN = 12.0F;
    private static final float CHAT_LIFT = 64.0F;

    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue tpsAnim = new AnimatedValue();
    private final AnimatedValue liftAnim = new AnimatedValue();
    private final List<Segment> row = new ArrayList<>(1);

    private long lastTickNanos = -1L;
    private float tps = 20.0F;

    public TpsElement() {
        super("tps", "TPS");
        EventManager.subscribe(this);
    }

    @EventTarget
    public void onGameTick(GameTickEvent event) {
        long now = System.nanoTime();
        if (this.lastTickNanos > 0L) {
            double deltaSeconds = (now - this.lastTickNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0.001) {
                this.tps = (float) Math.min(20.0, 1.0 / deltaSeconds);
            }
        }
        this.lastTickNanos = now;
    }

    @Override
    protected boolean accentBarOnRight() {
        return true;
    }

    @Override
    protected boolean customPosition() {
        return true;
    }

    @Override
    protected void applyCustomPosition(Minecraft mc, float unit) {
        boolean chatOpen = mc.gui.screen() instanceof ChatScreen;
        float lift = this.liftAnim.update(chatOpen ? CHAT_LIFT * unit : 0.0F, 0.18F, System.nanoTime());
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        this.x = screenWidth - this.width - MARGIN * unit;
        this.y = mc.getWindow().getGuiScaledHeight() - this.height - MARGIN * unit - lift;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        float tps = this.tpsAnim.update(this.tps, 0.15F, now);

        this.row.clear();
        if (HudFeature.informationShows(ITEM_TPS)) {
            this.row.add(unit(Textures.Hud.ICON_MISC, String.format(Locale.ROOT, "%.1f", tps), " tps"));
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
