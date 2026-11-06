package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.joml.Matrix3x2fStack;
import win.furryhook.context.RenderContext;
import win.furryhook.feature.impl.visual.HudFeature;
import win.furryhook.menu.core.MenuConfigStore;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.gui.Render2DUtil;

public abstract class HudElement {
    private static final long APPEAR_MS = 200L;

    private final String id;
    private final String displayName;

    private final String configKeyX;
    private final String configKeyY;
    private final String configKeyAlwaysShow;
    protected float x;
    protected float y;
    protected float width;
    protected float height;
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;
    private boolean wasHidden = true;
    private long appearStart;
    private boolean positionInitialized;
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;

    protected HudElement(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.configKeyX = "hud." + id + ".x";
        this.configKeyY = "hud." + id + ".y";
        this.configKeyAlwaysShow = "hud." + id + ".alwaysShow";
    }

    public final String id() {
        return this.id;
    }

    public final String displayName() {
        return this.displayName;
    }

    protected final boolean alwaysShow() {
        return MenuConfigStore.getBoolean(this.configKeyAlwaysShow, false);
    }

    public final void setAlwaysShow(boolean value) {
        MenuConfigStore.save(data -> data.addProperty(this.configKeyAlwaysShow, value));
    }

    public final boolean contains(float mouseX, float mouseY) {
        return this.width > 0.5F && this.height > 0.5F
                && mouseX >= this.x && mouseX <= this.x + this.width
                && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    public final void render(Minecraft mc, float unit) {
        layout(mc, unit);
        if (this.width <= 0.5F || this.height <= 0.5F) {
            this.wasHidden = true;
            return;
        }
        if (this.wasHidden) {
            this.wasHidden = false;
            this.appearStart = System.currentTimeMillis();
        }

        if (customPosition()) {
            applyCustomPosition(mc, unit);
            draw(mc, unit);
            return;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float freeWidth = Math.max(0.0F, screenWidth - this.width);
        float freeHeight = Math.max(0.0F, screenHeight - this.height);
        boolean contentResizeOnly = preservePositionOnContentResize()
                && this.positionInitialized
                && screenWidth == this.lastScreenWidth
                && screenHeight == this.lastScreenHeight;
        if (!this.dragging) {
            if (!contentResizeOnly) {
                this.x = MenuConfigStore.getFloat(this.configKeyX, defaultX(unit) / Math.max(1.0F, freeWidth)) * freeWidth;
                this.y = MenuConfigStore.getFloat(this.configKeyY, defaultY(unit) / Math.max(1.0F, freeHeight)) * freeHeight;
            }
        }
        this.positionInitialized = true;
        this.lastScreenWidth = screenWidth;
        this.lastScreenHeight = screenHeight;
        this.x = contentResizeOnly && !this.dragging ? Math.max(0.0F, this.x) : MathUtil.clamp(this.x, 0.0F, freeWidth);
        this.y = MathUtil.clamp(this.y, 0.0F, freeHeight);
        if (centerHorizontally()) {
            this.x = freeWidth / 2.0F;
        }

        draw(mc, unit);
    }

    /**
     * When true, this element manages its own x/y entirely (e.g. a fixed screen-corner setting)
     * instead of the normal drag/store position system - {@link #applyCustomPosition} must set
     * {@link #x}/{@link #y} itself, and the element can no longer be dragged.
     */
    protected boolean customPosition() {
        return false;
    }

    protected void applyCustomPosition(Minecraft mc, float unit) {
    }

    protected boolean centerHorizontally() {
        return false;
    }

    protected boolean preservePositionOnContentResize() {
        return false;
    }

    private static final int SOLID_BACKGROUND = ColorUtil.rgba(19, 20, 22, 255);

    /**
     * Draws an opaque backing behind the glass panel, scaled by the HUD "Transparency" setting.
     * The glass() call's own alpha is a fixed translucent baseline (capped around 50% alpha by
     * design), so it can never reach fully solid on its own - this backing is what lets the
     * setting actually reach 100% opacity instead of topping out at that baseline.
     */
    protected final void drawPanelBacking(float unit, float radius, float alpha) {
        drawPanelBacking(this.x, this.y, this.width, this.height, radius, alpha);
    }

    protected final void drawPanelBacking(float panelX, float panelY, float panelWidth, float panelHeight, float radius, float alpha) {
        drawPanelBacking(panelX, panelY, panelWidth, panelHeight, radius, alpha, HudFeature.backgroundOpacity());
    }

    protected final void drawPanelBacking(float panelX, float panelY, float panelWidth, float panelHeight, float radius, float alpha, float opacity) {
        if (opacity <= 0.001F || panelWidth <= 0.0F || panelHeight <= 0.0F) {
            return;
        }
        Render2DUtil.rect(panelX, panelY, panelWidth, panelHeight)
                .color(ColorUtil.multiplyAlpha(SOLID_BACKGROUND, alpha * opacity))
                .radius(radius)
                .draw();
    }

    private static final long SHINE_PERIOD_MS = 3200L;
    private static final float SHINE_WIDTH_UNITS = 26.0F;
    private static final float SHINE_ANGLE_RADIANS = (float) Math.toRadians(20.0);
    private static final int SHINE_COLOR = ColorUtil.rgba(255, 255, 255, 26);
    private static final int SHINE_TRANSPARENT = ColorUtil.rgba(255, 255, 255, 0);

    protected final void drawShine(float unit) {
        drawShine(this.x, this.y, this.width, this.height, unit);
    }

    protected final void drawShine(float panelX, float panelY, float panelWidth, float panelHeight, float unit) {
        if (panelWidth <= 1.0F || panelHeight <= 1.0F || !HudFeature.glareEnabled()) {
            return;
        }
        GuiGraphicsExtractor extractor = RenderContext.currentGuiGraphicsExtractor();
        if (extractor == null) {
            return;
        }

        long now = System.currentTimeMillis();
        float progress = (now % SHINE_PERIOD_MS) / (float) SHINE_PERIOD_MS;
        float barWidth = SHINE_WIDTH_UNITS * unit;
        float half = barWidth / 2.0F;
        float travel = panelWidth + barWidth * 2.0F;
        float barX = panelX - barWidth + progress * travel;
        float centerX = barX + half;
        float centerY = panelY + panelHeight / 2.0F;
        float barHeight = panelHeight * 1.8F;
        float barTop = centerY - barHeight / 2.0F;

        Render2DUtil.flush();
        Render2DUtil.pushScissor(panelX, panelY, panelWidth, panelHeight);
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.rotate(SHINE_ANGLE_RADIANS);
        pose.translate(-centerX, -centerY);
        Render2DUtil.rect(barX, barTop, half, barHeight)
                .color(SHINE_TRANSPARENT, SHINE_COLOR, SHINE_COLOR, SHINE_TRANSPARENT)
                .draw();
        Render2DUtil.rect(barX + half, barTop, half, barHeight)
                .color(SHINE_COLOR, SHINE_TRANSPARENT, SHINE_TRANSPARENT, SHINE_COLOR)
                .draw();
        Render2DUtil.flush();
        pose.popMatrix();
        Render2DUtil.popScissor();
    }

    protected final float appearAlpha() {
        float progress = MathUtil.clamp01((System.currentTimeMillis() - this.appearStart) / (float) APPEAR_MS);
        return progress * progress * (3.0F - 2.0F * progress);
    }

    protected static final int DIVIDER_COLOR = win.furryhook.utils.ColorUtil.rgba(255, 255, 255, 25);

    protected final float divider(float cursor, float centerY, float heightUnits, float gapUnits, float unit, float alpha) {
        cursor += gapUnits * unit;
        win.furryhook.utils.render.gui.Render2DUtil.rect(
                        cursor, centerY - heightUnits * unit / 2.0F, 1.0F * unit, heightUnits * unit)
                .color(win.furryhook.utils.ColorUtil.multiplyAlpha(DIVIDER_COLOR, alpha))
                .draw();
        return cursor + (1.0F + gapUnits) * unit;
    }

    protected abstract void layout(Minecraft mc, float unit);

    protected float defaultX(float unit) {
        return 20.0F * unit;
    }

    protected float defaultY(float unit) {
        return 20.0F * unit;
    }

    protected abstract void draw(Minecraft mc, float unit);

    protected static boolean showcase(Minecraft mc) {
        return mc.gui.screen() instanceof ChatScreen || mc.gui.screen() instanceof InventoryScreen;
    }

    public final boolean startDrag(float mouseX, float mouseY) {
        if (customPosition()) {
            return false;
        }
        if (mouseX < this.x || mouseX > this.x + this.width || mouseY < this.y || mouseY > this.y + this.height) {
            return false;
        }
        this.dragging = true;
        this.dragOffsetX = mouseX - this.x;
        this.dragOffsetY = mouseY - this.y;
        return true;
    }

    public final void dragTo(float mouseX, float mouseY) {
        if (!centerHorizontally()) {
            this.x = mouseX - this.dragOffsetX;
        }
        this.y = mouseY - this.dragOffsetY;
    }

    public final boolean isDragging() {
        return this.dragging;
    }

    public final float x() {
        return this.x;
    }

    public final float y() {
        return this.y;
    }

    public final float width() {
        return this.width;
    }

    public final float height() {
        return this.height;
    }

    public final boolean isHorizontallyCentered() {
        return centerHorizontally();
    }

    public final void snapTo(float x, float y) {
        if (!this.dragging) {
            return;
        }
        if (!centerHorizontally()) {
            this.x = x;
        }
        this.y = y;
    }

    public final boolean overlaps(float x, float y, float width, float height) {
        return this.width > 0.5F
                && this.height > 0.5F
                && x < this.x + this.width
                && x + width > this.x
                && y < this.y + this.height
                && y + height > this.y;
    }

    public final void resetPosition() {
        this.dragging = false;
        this.positionInitialized = false;
        this.lastScreenWidth = -1;
        this.lastScreenHeight = -1;
    }

    public final void stopDrag(Minecraft mc) {
        if (!this.dragging) {
            return;
        }
        this.dragging = false;
        float freeWidth = Math.max(1.0F, mc.getWindow().getGuiScaledWidth() - this.width);
        float freeHeight = Math.max(1.0F, mc.getWindow().getGuiScaledHeight() - this.height);
        float fractionX = MathUtil.clamp01(this.x / freeWidth);
        float fractionY = MathUtil.clamp01(this.y / freeHeight);
        MenuConfigStore.save(data -> {
            data.addProperty(this.configKeyX, fractionX);
            data.addProperty(this.configKeyY, fractionY);
        });
    }
}
