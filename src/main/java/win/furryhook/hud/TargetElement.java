package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import win.furryhook.context.RenderContext;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.impl.combat.TriggerBotFeature;
import win.furryhook.feature.impl.visual.HudFeature;
import win.furryhook.mixin.accessor.ModelPartAccessor;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.PlayerHead;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.combat.HealthUtil;
import win.furryhook.utils.render.gui.TextAlign;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TargetElement extends HudElement {
    public static final String STYLE_DEFAULT = "Default";
    public static final String STYLE_GSMAR = "Gsmar";

    private static final int BAR_TRACK_COLOR = Theme.Colors.OUTLINES_MEDIUM;
    private static final int ABSORPTION_DARK_COLOR = ColorUtil.rgb(184, 125, 18);
    private static final int ABSORPTION_LIGHT_COLOR = ColorUtil.rgb(255, 226, 92);

    private static final float ITEM_SIZE = 12.0F;
    private static final float ITEM_GAP = 2.0F;
    private static final float TEXT_SIZE = 8.5F;
    private static final float BAR_HEIGHT = 4.0F;
    private static final float BAR_INSET = 1.0F;

    private record FaceIcon(Identifier texture, float u0, float v0, float u1, float v1) {
    }

    private static final EquipmentSlot[] EQUIPMENT_ORDER = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private record Style(
            float width,
            float height,
            float radius,
            float padding,
            float gap,
            float headSize,
            boolean showItems
    ) {
    }

    private static final Style MINI = new Style(100.0F, 40.0F, 8.0F, 5.0F, 5.0F, 22.0F, false);
    private static final float BAR_GAP = 4.0F;

    /**
     * Second target style: head + name/health/distance lines, with a green health bar and a
     * blue armor bar stacked under them (armor points out of {@link #GSMAR_MAX_ARMOR}).
     * Its record width is unused - {@link #gsmarWidth} measures the text instead.
     */
    private static final Style GSMAR = new Style(0.0F, 40.0F, 5.0F, 4.0F, 5.5F, 22.0F, false);
    /** Digit template the health/distance lines are sized against - see {@link #gsmarWidth}. */
    private static final String GSMAR_WIDTH_TEMPLATE = "Distance: 000.0m";
    /** Breathing room between the longest text line and the panel's right padding. */
    private static final float GSMAR_TEXT_TRAIL = 6.0F;
    private static final float GSMAR_MIN_WIDTH = 82.0F;
    private static final float GSMAR_MAX_WIDTH = 136.0F;
    /**
     * Shrinks the whole Gsmar panel relative to the shared HUD unit, keeping its proportions.
     * Tuned so the panel lands just under the {@link #MINI} default style: 38 tall against its
     * 40, and roughly 88 wide against its 100.
     */
    private static final float GSMAR_SCALE = 0.95F;
    private static final int GSMAR_HEALTH_DARK = ColorUtil.rgb(16, 94, 34);
    private static final int GSMAR_HEALTH_LIGHT = ColorUtil.rgb(138, 255, 146);
    private static final int GSMAR_ARMOR_DARK = ColorUtil.rgb(12, 62, 154);
    private static final int GSMAR_ARMOR_LIGHT = ColorUtil.rgb(124, 214, 255);
    private static final float GSMAR_NAME_SIZE = 8.5F;
    private static final float GSMAR_LINE_SIZE = 7.0F;
    private static final float GSMAR_NAME_CENTER = 4.5F;
    private static final float GSMAR_HEALTH_CENTER = 12.5F;
    private static final float GSMAR_DISTANCE_CENTER = 20.0F;
    private static final float GSMAR_BAR_HEIGHT = 2.5F;
    private static final float GSMAR_BAR_TOP_GAP = 3.0F;
    private static final float GSMAR_BAR_GAP = 2.0F;
    private static final float GSMAR_MAX_ARMOR = 20.0F;

    // ---- Redesigned default panel: dark header (head + name/hp/distance + right HP bar) over a
    // translucent equipment plate, in the same twin-plate language as the Keybinds HUD. ----
    private static final float D_PAD = 4.0F;
    private static final float D_HEAD = 22.0F;
    private static final float D_GAP = 6.5F;
    private static final float D_NAME_SIZE = 8.0F;
    private static final float D_LINE_SIZE = 6.0F;
    private static final float D_NAME_CENTER = 5.0F;
    private static final float D_HP_CENTER = 12.0F;
    private static final float D_DISTANCE_CENTER = 18.0F;
    private static final float D_HPBAR_W = 4.5F;
    private static final float D_HPBAR_GAP = 5.0F;
    private static final float D_RADIUS = 6.0F;
    private static final float D_ITEM = 11.0F;
    private static final float D_ITEM_GAP = 2.5F;
    private static final float D_BODY_GAP = 3.0F;
    private static final float D_BODY_OVERLAP = 6.0F;
    private static final float D_MIN_WIDTH = 96.0F;
    private static final String D_LINE_TEMPLATE = "Distance: 000.0";
    private static final int D_HEADER_BG = ColorUtil.rgb(30, 30, 30);
    private static final int D_BODY_BG = ColorUtil.rgba(19, 20, 22, 179);

    private int defaultItemCount;

    private Style style = MINI;
    private LivingEntity target;
    private LivingEntity smoothedFor;
    private float smoothedHealth;
    private float smoothedAbsorption;
    private float smoothedArmor;
    private long lastFrameTime;

    private LivingEntity fadeEntity;
    private float fadeAlpha;
    private long lastFadeTime;

    private final TargetHudParticles particles = new TargetHudParticles();
    private LivingEntity damageFor;
    private float lastTotalHealth;
    /** Half-hearts the target lost since the previous frame - drives every emitter below. */
    private float frameDamage;
    /** Rolls over sub-particle amounts so the drain trail stays smooth at any frame rate. */
    private float dripCarry;

    public TargetElement() {
        super("target", "Target");
    }

    @Override
    protected float defaultY(float unit) {
        return 340.0F * unit;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        this.style = HudFeature.gsmarTarget() ? GSMAR : MINI;
        unit *= localScale();
        this.target = resolveTarget(mc);
        if (this.target != null) {
            this.fadeEntity = this.target;
        }
        updateFade();
        if (this.fadeEntity == null || (this.target == null && this.fadeAlpha <= 0.02F)) {
            this.width = 0.0F;
            this.height = 0.0F;
            return;
        }
        LivingEntity shown = this.target != null ? this.target : this.fadeEntity;
        if (this.style == GSMAR) {
            this.width = gsmarWidth(shown, unit);
            this.height = this.style.height() * unit;
        } else {
            this.defaultItemCount = countEquipment(shown);
            this.width = defaultWidth(shown, unit);
            this.height = defaultHeight(unit);
        }
    }

    private int countEquipment(LivingEntity entity) {
        if (entity == null) {
            return 0;
        }
        int count = 0;
        for (EquipmentSlot slot : EQUIPMENT_ORDER) {
            if (!entity.getItemBySlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private List<ItemStack> equipmentStacks(LivingEntity entity) {
        List<ItemStack> stacks = new ArrayList<>(EQUIPMENT_ORDER.length);
        for (EquipmentSlot slot : EQUIPMENT_ORDER) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    private float defaultHeight(float unit) {
        float total = D_HEAD + 2.0F * D_PAD
                + (this.defaultItemCount > 0 ? D_BODY_GAP + D_ITEM + D_PAD : 0.0F);
        return total * unit;
    }

    private float defaultWidth(LivingEntity entity, float unit) {
        MsdfFont semi = UiFonts.sfPro(600);
        MsdfFont reg = UiFonts.sfPro(400);
        float nameSize = D_NAME_SIZE * unit;
        float lineSize = D_LINE_SIZE * unit;
        String name = entity != null ? entity.getName().getString() : "";
        float textWidth = Math.max(
                semi.measureWidth(name, nameSize, nameSize * UiFontStyle.SEMIBOLD.letterSpacingEm()),
                reg.measureWidth(D_LINE_TEMPLATE, lineSize, 0.0F));
        float headerWidth = (D_PAD + D_HEAD + D_GAP) * unit + textWidth
                + (D_HPBAR_GAP + D_HPBAR_W + D_PAD) * unit;
        float bodyWidth = this.defaultItemCount > 0
                ? (D_PAD * 2.0F + this.defaultItemCount * D_ITEM + (this.defaultItemCount - 1) * D_ITEM_GAP) * unit
                : 0.0F;
        return Math.max(Math.max(D_MIN_WIDTH * unit, headerWidth), bodyWidth);
    }

    @Override
    protected boolean preservePositionOnContentResize() {
        // The Gsmar panel is name-width driven, so its left edge must not slide when the
        // target changes to one with a longer or shorter name.
        return true;
    }

    /**
     * Width that just fits the longest of the three text lines. The health/distance lines are
     * measured against a fixed digit template rather than their live values, so the panel keeps
     * a stable width instead of breathing every time the distance ticks over.
     */
    private float gsmarWidth(LivingEntity entity, float unit) {
        MsdfFont font = UiFonts.sfProDisplay();
        float nameSize = GSMAR_NAME_SIZE * unit;
        float lineSize = GSMAR_LINE_SIZE * unit;
        float textWidth = Math.max(
                font.measureWidth(
                        entity.getName().getString(),
                        nameSize,
                        nameSize * UiFontStyle.SEMIBOLD.letterSpacingEm()),
                font.measureWidth(
                        GSMAR_WIDTH_TEMPLATE,
                        lineSize,
                        lineSize * UiFontStyle.MEDIUM.letterSpacingEm())
        );
        float content = (this.style.padding() * 2.0F + this.style.headSize() + this.style.gap()
                + GSMAR_TEXT_TRAIL) * unit + textWidth;
        return Math.clamp(content, GSMAR_MIN_WIDTH * unit, GSMAR_MAX_WIDTH * unit);
    }

    private float localScale() {
        // Both styles render at the same reduced footprint relative to the shared HUD unit.
        return GSMAR_SCALE;
    }

    private void updateFade() {
        long now = System.currentTimeMillis();
        float delta = this.lastFadeTime == 0L ? 0.016F : Math.min(100L, now - this.lastFadeTime) / 1000.0F;
        this.lastFadeTime = now;
        float goal = this.target != null ? 1.0F : 0.0F;
        float step = Math.clamp(delta * 12.0F, 0.0F, 1.0F);
        this.fadeAlpha += (goal - this.fadeAlpha) * step;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        LivingEntity entity = this.target != null ? this.target : this.fadeEntity;
        if (entity == null) {
            return;
        }
        float alpha = this.fadeAlpha;
        unit *= localScale();

        this.frameDamage = trackDamage(entity);
        this.particles.update();

        if (this.style == GSMAR) {
            // Gsmar stays translucent (~50%) with a blurred backdrop instead of an opaque backing.
            Render2DUtil.rect(this.x, this.y, this.width, this.height)
                    .glass(alpha, 0.5F * unit, 8.0F * unit)
                    .radius(this.style.radius() * unit)
                    .draw();
            drawGsmar(mc, entity, unit, alpha);
            return;
        }

        drawDefault(mc, entity, unit, alpha);
    }

    private void drawDefault(Minecraft mc, LivingEntity entity, float unit, float alpha) {
        float x = this.x;
        float y = this.y;
        float width = this.width;
        float headerHeight = (D_HEAD + 2.0F * D_PAD) * unit;

        List<ItemStack> stacks = equipmentStacks(entity);
        boolean hasBody = !stacks.isEmpty() && this.height - headerHeight > 0.5F;

        // ---- Equipment plate: translucent + blurred, tucked up behind the header so the two
        // plates read as one joined shape. ----
        if (hasBody) {
            float bodyTop = y + headerHeight - D_BODY_OVERLAP * unit;
            float bodyBottom = y + this.height;
            float bodyWidth = Math.min(width,
                    (D_PAD * 2.0F + stacks.size() * D_ITEM + (stacks.size() - 1) * D_ITEM_GAP) * unit);
            Render2DUtil.rect(x, bodyTop, bodyWidth, bodyBottom - bodyTop)
                    .color(ColorUtil.multiplyAlpha(D_BODY_BG, alpha))
                    .radius(D_RADIUS * unit)
                    .border(Math.max(0.5F, 0.5F * unit),
                            ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_SMALL, alpha))
                    .blur(6.0F * unit, alpha)
                    .draw();
        }

        // ---- Header plate: solid dark, on top of the body. ----
        Render2DUtil.rect(x, y, width, headerHeight)
                .color(ColorUtil.multiplyAlpha(D_HEADER_BG, alpha))
                .radius(D_RADIUS * unit)
                .draw();

        // Left design accent bar.
        float barWidth = 1.6F * unit;
        float barHeight = headerHeight * 0.5F;
        Render2DUtil.rect(x, y + (headerHeight - barHeight) * 0.5F, barWidth, barHeight)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), alpha))
                .radius(0.0F, barWidth * 0.5F, barWidth * 0.5F, 0.0F)
                .draw();

        // Head.
        float headTop = y + D_PAD * unit;
        float headSize = D_HEAD * unit;
        drawHead(entity, x + D_PAD * unit, headTop, headSize, headSize * 0.18F, alpha);
        drawEatingOverlay(mc, entity, x + D_PAD * unit, headTop, headSize, headSize * 0.18F, alpha);

        // Right vertical HP bar.
        float hpBarWidth = D_HPBAR_W * unit;
        float hpBarX = x + width - D_PAD * unit - hpBarWidth;
        float hpBarTop = y + D_PAD * unit;
        float hpBarHeight = headerHeight - 2.0F * D_PAD * unit;
        float health = HealthUtil.getHealth(entity);
        float absorption = Math.max(0.0F, entity.getAbsorptionAmount());
        float maxHealth = Math.max(1.0F, entity.getMaxHealth() + absorption);
        float fraction = Math.clamp(
                smoothHealth(entity, health, absorption, entity.getArmorValue()) / maxHealth, 0.0F, 1.0F);
        Render2DUtil.rect(hpBarX, hpBarTop, hpBarWidth, hpBarHeight)
                .color(ColorUtil.multiplyAlpha(BAR_TRACK_COLOR, alpha))
                .radius(hpBarWidth / 2.0F)
                .draw();
        float fillTop = hpBarTop + hpBarHeight;
        if (fraction > 0.0F) {
            // Clamp to at least the bar width so low-but-nonzero health still shows a rounded nub
            // instead of vanishing (e.g. 4/20 HP was rendering as empty).
            float fillHeight = Math.max(hpBarWidth, hpBarHeight * fraction);
            fillTop = hpBarTop + hpBarHeight - fillHeight;
            Render2DUtil.rect(hpBarX, fillTop, hpBarWidth, fillHeight)
                    .color(ColorUtil.multiplyAlpha(Theme.getAccent(), alpha))
                    .radius(hpBarWidth / 2.0F)
                    .draw();
        }

        // Sparks shedding off the draining edge of the bar, plus a burst off the head on hit.
        if (this.target != null) {
            if (HudFeature.targetHealthParticles()) {
                if (this.frameDamage > 0.01F) {
                    this.particles.emitHealthVertical(hpBarX, hpBarWidth, fillTop, unit, Theme.getAccent(),
                            TargetHudParticles.countFor(this.frameDamage, 4, 14));
                }
                int drip = drainDrip(health, absorption);
                if (drip > 0) {
                    this.particles.emitHealthVertical(hpBarX, hpBarWidth, fillTop, unit, Theme.getAccent(), drip);
                }
            }
            if (this.frameDamage > 0.01F && HudFeature.targetHeadParticles()) {
                this.particles.emitHeadBurst(x + D_PAD * unit, headTop, headSize, unit, Theme.getAccent(),
                        TargetHudParticles.countFor(this.frameDamage, 6, 18));
            }
        }

        // Text block: name (semibold) + HP / Distance (regular).
        MsdfFont semi = UiFonts.sfPro(600);
        MsdfFont reg = UiFonts.sfPro(400);
        float nameSize = D_NAME_SIZE * unit;
        float lineSize = D_LINE_SIZE * unit;
        float infoX = x + D_PAD * unit + headSize + D_GAP * unit;
        float textRight = hpBarX - D_HPBAR_GAP * unit;
        float textLimit = Math.max(12.0F * unit, textRight - infoX);
        float distance = mc.player != null ? mc.player.distanceTo(entity) : 0.0F;

        String name = semi.ellipsize(entity.getName().getString(), nameSize,
                nameSize * UiFontStyle.SEMIBOLD.letterSpacingEm(), textLimit);
        String hpText = reg.ellipsize(String.format(Locale.ROOT, "HP: %.1f", health), lineSize, 0.0F, textLimit);
        String distanceText = reg.ellipsize(
                String.format(Locale.ROOT, "Distance: %.1f", distance), lineSize, 0.0F, textLimit);

        Render2DUtil.text(infoX, semi.centeredTextY(headTop + D_NAME_CENTER * unit, nameSize), nameSize, name)
                .font(semi)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, alpha))
                .draw();
        Render2DUtil.text(infoX, reg.centeredTextY(headTop + D_HP_CENTER * unit, lineSize), lineSize, hpText)
                .font(reg)
                .style(UiFontStyle.REGULAR)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TEXT, alpha))
                .draw();
        Render2DUtil.text(infoX, reg.centeredTextY(headTop + D_DISTANCE_CENTER * unit, lineSize), lineSize, distanceText)
                .font(reg)
                .style(UiFontStyle.REGULAR)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TEXT, alpha))
                .draw();

        // Equipment icons row inside the body plate. Item rendering can't fade with the panel
        // (the item renderer ignores our alpha), so only draw while a live target is present and
        // the panel is essentially opaque - otherwise the icons would linger as it fades out.
        if (hasBody && this.target != null && alpha >= 0.75F) {
            float itemY = y + headerHeight + D_BODY_GAP * unit;
            drawItemRow(mc, stacks, x + D_PAD * unit, itemY, D_ITEM * unit, D_ITEM_GAP * unit);
        }

        this.particles.draw(alpha);
    }

    private void drawItemRow(Minecraft mc, List<ItemStack> stacks, float startX, float y, float itemSize, float gap) {
        GuiGraphicsExtractor extractor = RenderContext.currentGuiGraphicsExtractor();
        if (extractor == null) {
            return;
        }
        Render2DUtil.flush();
        Matrix3x2fStack pose = extractor.pose();
        float guiScale = (float) mc.getWindow().getGuiScale();
        float scale = itemSize / 16.0F;
        float itemY = Math.round(y * guiScale) / guiScale;
        float cursor = startX;
        for (ItemStack stack : stacks) {
            float itemX = Math.round(cursor * guiScale) / guiScale;
            pose.pushMatrix();
            pose.translate(itemX, itemY);
            pose.scale(scale);
            extractor.item(stack, 0, 0);
            pose.popMatrix();
            cursor += itemSize + gap;
        }
    }

    private void drawGsmar(Minecraft mc, LivingEntity entity, float unit, float alpha) {
        MsdfFont font = UiFonts.sfProDisplay();
        float padding = this.style.padding() * unit;
        float contentX = this.x + padding;
        float contentRight = this.x + this.width - padding;
        float headTop = this.y + padding;
        float headSize = this.style.headSize() * unit;

        drawHead(entity, contentX, headTop, headSize, headSize * 0.12F, alpha);
        drawEatingOverlay(mc, entity, contentX, headTop, headSize, headSize * 0.12F, alpha);

        float infoX = contentX + headSize + this.style.gap() * unit;
        float infoWidth = contentRight - infoX - GSMAR_TEXT_TRAIL * unit;
        float nameSize = GSMAR_NAME_SIZE * unit;
        float lineSize = GSMAR_LINE_SIZE * unit;
        float nameSpacing = nameSize * UiFontStyle.SEMIBOLD.letterSpacingEm();
        float lineSpacing = lineSize * UiFontStyle.MEDIUM.letterSpacingEm();

        float health = HealthUtil.getHealth(entity);
        float armor = Math.min(entity.getArmorValue(), GSMAR_MAX_ARMOR);
        float distance = mc.player != null ? mc.player.distanceTo(entity) : 0.0F;

        String name = font.ellipsize(entity.getName().getString(), nameSize, nameSpacing, infoWidth);
        String healthText = font.ellipsize(
                String.format(Locale.ROOT, "Health: %.1f", health), lineSize, lineSpacing, infoWidth);
        String distanceText = font.ellipsize(
                String.format(Locale.ROOT, "Distance: %.1fm", distance), lineSize, lineSpacing, infoWidth);

        Render2DUtil.text(infoX, font.centeredTextY(headTop + GSMAR_NAME_CENTER * unit, nameSize), nameSize, name)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, alpha))
                .draw();
        Render2DUtil.text(infoX, font.centeredTextY(headTop + GSMAR_HEALTH_CENTER * unit, lineSize), lineSize, healthText)
                .style(UiFontStyle.MEDIUM)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TEXT, alpha))
                .draw();
        Render2DUtil.text(infoX, font.centeredTextY(headTop + GSMAR_DISTANCE_CENTER * unit, lineSize), lineSize, distanceText)
                .style(UiFontStyle.MEDIUM)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TEXT, alpha))
                .draw();

        float absorption = Math.max(0.0F, entity.getAbsorptionAmount());
        float maxHealth = Math.max(1.0F, entity.getMaxHealth() + absorption);
        float smoothedHealthValue = smoothHealth(entity, health, absorption, armor);

        float barWidth = contentRight - contentX;
        float barHeight = GSMAR_BAR_HEIGHT * unit;
        float healthBarY = headTop + headSize + GSMAR_BAR_TOP_GAP * unit;
        float armorBarY = healthBarY + barHeight + GSMAR_BAR_GAP * unit;

        drawGsmarBar(contentX, healthBarY, barWidth, barHeight, smoothedHealthValue / maxHealth,
                GSMAR_HEALTH_DARK, GSMAR_HEALTH_LIGHT, alpha);
        drawGsmarBar(contentX, armorBarY, barWidth, barHeight, this.smoothedArmor / GSMAR_MAX_ARMOR,
                GSMAR_ARMOR_DARK, GSMAR_ARMOR_LIGHT, alpha);

        if (this.target != null && this.frameDamage > 0.01F && HudFeature.targetHeadParticles()) {
            this.particles.emitHeadBurst(contentX, headTop, headSize, unit, GSMAR_HEALTH_LIGHT,
                    TargetHudParticles.countFor(this.frameDamage, 6, 18));
        }
        this.particles.draw(alpha);
    }

    private static void drawGsmarBar(
            float x,
            float y,
            float width,
            float height,
            float fraction,
            int darkColor,
            int lightColor,
            float alpha
    ) {
        float radius = height / 2.0F;
        Render2DUtil.rect(x, y, width, height)
                .color(ColorUtil.multiplyAlpha(BAR_TRACK_COLOR, alpha))
                .radius(radius)
                .draw();

        float fillWidth = width * Math.clamp(fraction, 0.0F, 1.0F);
        if (fillWidth < height) {
            return;
        }
        Render2DUtil.rect(x, y, fillWidth, height)
                .color(
                        ColorUtil.multiplyAlpha(darkColor, alpha),
                        ColorUtil.multiplyAlpha(lightColor, alpha),
                        ColorUtil.multiplyAlpha(lightColor, alpha),
                        ColorUtil.multiplyAlpha(darkColor, alpha)
                )
                .radius(radius)
                .draw();
    }

    /**
     * When the target is eating or drinking, dims the head, overlays the consumed item and prints
     * the remaining use time (seconds) under it. Only shown for a live target so it never lingers
     * as the panel fades out.
     */
    private void drawEatingOverlay(Minecraft mc, LivingEntity entity, float headX, float headTop,
                                   float headSize, float headRadius, float alpha) {
        if (this.target == null || !entity.isUsingItem()) {
            return;
        }
        ItemStack use = entity.getUseItem();
        if (use.isEmpty()) {
            return;
        }
        ItemUseAnimation animation = use.getUseAnimation();
        if (animation != ItemUseAnimation.EAT && animation != ItemUseAnimation.DRINK) {
            return;
        }

        Render2DUtil.rect(headX, headTop, headSize, headSize)
                .color(ColorUtil.multiplyAlpha(ColorUtil.rgba(0, 0, 0, 165), alpha))
                .radius(headRadius)
                .draw();

        float itemSize = headSize * 0.6F;
        float itemX = headX + (headSize - itemSize) / 2.0F;
        float itemY = headTop + (headSize - itemSize) / 2.0F - headSize * 0.08F;
        if (alpha >= 0.75F) {
            drawItemRow(mc, List.of(use), itemX, itemY, itemSize, 0.0F);
        }

        int remainingTicks = Math.max(0, entity.getUseItemRemainingTicks());
        String timer = String.format(Locale.ROOT, "%.1f", remainingTicks / 20.0F);
        MsdfFont font = UiFonts.sfPro(600);
        float timerSize = headSize * 0.26F;
        float timerWidth = font.measureWidth(timer, timerSize, 0.0F);
        Render2DUtil.text(headX + (headSize - timerWidth) / 2.0F,
                        font.centeredTextY(headTop + headSize - headSize * 0.17F, timerSize), timerSize, timer)
                .font(font)
                .style(UiFontStyle.SEMIBOLD)
                .outline(ColorUtil.multiplyAlpha(ColorUtil.rgba(0, 0, 0, 200), alpha), 0.35F)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, alpha))
                .draw();
    }

    private void drawHead(LivingEntity entity, float x, float y, float size, float radius, float alpha) {
        int tint = ColorUtil.multiplyAlpha(ColorUtil.WHITE, alpha);
        if (entity instanceof AbstractClientPlayer player) {
            PlayerHead.draw(x, y, size, player.getSkin().body().texturePath(), radius, tint);
        } else {
            Render2DUtil.rect(x, y, size, size)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_MEDIUM, alpha))
                    .radius(radius)
                    .draw();
            FaceIcon icon = resolveFaceIcon(entity);
            if (icon != null) {
                Render2DUtil.texture(x, y, size, size, icon.texture())
                        .managed()
                        .uv(icon.u0(), icon.v0(), icon.u1(), icon.v1())
                        .radius(radius)
                        .color(tint)
                        .draw();
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private FaceIcon resolveFaceIcon(LivingEntity entity) {
        EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = (EntityRenderState) renderer.createRenderState(entity, 1.0F);
        if (!(renderer instanceof LivingEntityRenderer livingRenderer)
                || !(state instanceof LivingEntityRenderState livingState)) {
            return null;
        }

        Identifier texture = livingRenderer.getTextureLocation(livingState);
        Model<?> model = livingRenderer.getModel();
        ModelPart root = model.root();
        var lookup = root.createPartLookup();
        ModelPart iconPart = lookup.apply("head");
        if (iconPart == null) {
            iconPart = lookup.apply("body");
        }
        if (iconPart == null) {
            iconPart = root;
        }

        FaceIcon icon = findLargestFrontFace(texture, iconPart);
        return icon != null || iconPart == root ? icon : findLargestFrontFace(texture, root);
    }

    private static FaceIcon findLargestFrontFace(Identifier texture, ModelPart part) {
        FaceIcon result = null;
        float largestArea = 0.0F;
        for (ModelPart candidate : part.getAllParts()) {
            for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) candidate).furryhook$getCubes()) {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    if (polygon.normal().z() > -0.9F) {
                        continue;
                    }
                    float minU = Float.POSITIVE_INFINITY;
                    float minV = Float.POSITIVE_INFINITY;
                    float maxU = Float.NEGATIVE_INFINITY;
                    float maxV = Float.NEGATIVE_INFINITY;
                    for (ModelPart.Vertex vertex : polygon.vertices()) {
                        minU = Math.min(minU, vertex.u());
                        minV = Math.min(minV, vertex.v());
                        maxU = Math.max(maxU, vertex.u());
                        maxV = Math.max(maxV, vertex.v());
                    }
                    float area = (maxU - minU) * (maxV - minV);
                    if (area > largestArea) {
                        largestArea = area;
                        result = new FaceIcon(texture, minU, minV, maxU, maxV);
                    }
                }
            }
        }
        return result;
    }

    private void drawItems(Minecraft mc, LivingEntity entity, float x, float y, float unit) {
        List<ItemStack> stacks = new ArrayList<>(EQUIPMENT_ORDER.length);
        for (EquipmentSlot slot : EQUIPMENT_ORDER) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        if (stacks.isEmpty()) {
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
        float scale = itemSize / 16.0F;
        float itemY = Math.round(y * guiScale) / guiScale;
        float cursor = x;
        for (ItemStack stack : stacks) {
            float itemX = Math.round(cursor * guiScale) / guiScale;
            pose.pushMatrix();
            pose.translate(itemX, itemY);
            pose.scale(scale);
            extractor.item(stack, 0, 0);
            pose.popMatrix();
            cursor += itemSize + ITEM_GAP * unit;
        }
    }

    private void drawHealthBar(LivingEntity entity, float health, float x, float y, float width, float unit, float alpha) {
        float absorption = Math.max(0.0F, entity.getAbsorptionAmount());
        float maxHealth = Math.max(1.0F, entity.getMaxHealth() + absorption);
        float fraction = Math.clamp(
                smoothHealth(entity, health, absorption, entity.getArmorValue()) / maxHealth, 0.0F, 1.0F);

        float barHeight = BAR_HEIGHT * unit;
        Render2DUtil.rect(x, y, width, barHeight)
                .color(ColorUtil.multiplyAlpha(BAR_TRACK_COLOR, alpha))
                .radius(barHeight / 2.0F)
                .draw();

        float inset = BAR_INSET * unit;
        float fillHeight = barHeight - inset * 2.0F;
        float innerWidth = width - inset * 2.0F;
        float fillWidth = innerWidth * fraction;
        if (fillWidth >= fillHeight) {
            float absorptionWidth = Math.min(
                    fillWidth,
                    innerWidth * Math.clamp(this.smoothedAbsorption / maxHealth, 0.0F, 1.0F)
            );
            float healthWidth = fillWidth - absorptionWidth;
            float radius = fillHeight / 2.0F;
            boolean hasHealth = healthWidth > 0.01F;
            boolean hasAbsorption = absorptionWidth > 0.01F;

            if (hasHealth) {
                float rightRadius = hasAbsorption ? 0.0F : radius;
                Render2DUtil.rect(x + inset, y + inset, healthWidth, fillHeight)
                        .color(ColorUtil.multiplyAlpha(Theme.getAccent(), alpha))
                        .radius(radius, rightRadius, rightRadius, radius)
                        .draw();
            }
            if (hasAbsorption) {
                float leftRadius = hasHealth ? 0.0F : radius;
                Render2DUtil.rect(x + inset + healthWidth, y + inset, absorptionWidth, fillHeight)
                        .color(
                                ColorUtil.multiplyAlpha(ABSORPTION_DARK_COLOR, alpha),
                                ColorUtil.multiplyAlpha(ABSORPTION_LIGHT_COLOR, alpha),
                                ColorUtil.multiplyAlpha(ABSORPTION_LIGHT_COLOR, alpha),
                                ColorUtil.multiplyAlpha(ABSORPTION_DARK_COLOR, alpha)
                        )
                        .radius(leftRadius, radius, radius, leftRadius)
                        .draw();
            }
        }
    }

    /**
     * Half-hearts (health + absorption) the target has lost since the last frame. Switching to a
     * new target resets the baseline and drops any sparks still in flight, so they never look like
     * they came off the new panel.
     */
    private float trackDamage(LivingEntity entity) {
        float total = HealthUtil.getHealth(entity) + Math.max(0.0F, entity.getAbsorptionAmount());
        if (this.damageFor != entity) {
            this.damageFor = entity;
            this.lastTotalHealth = total;
            this.dripCarry = 0.0F;
            this.particles.clear();
            return 0.0F;
        }
        float damage = Math.max(0.0F, this.lastTotalHealth - total);
        this.lastTotalHealth = total;
        return damage;
    }

    /**
     * Sparks that keep falling off the bar while the smoothed fill catches up to the real health,
     * so a hit reads as a drain rather than a single pop. Fractional amounts are carried over
     * between frames instead of being rounded away.
     */
    private int drainDrip(float health, float absorption) {
        float lag = this.smoothedHealth + this.smoothedAbsorption - (health + absorption);
        if (lag <= 0.1F) {
            this.dripCarry = 0.0F;
            return 0;
        }
        this.dripCarry += Math.min(1.2F, lag * 0.35F);
        int count = (int) this.dripCarry;
        this.dripCarry -= count;
        return count;
    }

    private float smoothHealth(LivingEntity entity, float health, float absorption, float armor) {
        long now = System.currentTimeMillis();
        float delta = this.lastFrameTime == 0L ? 0.016F : Math.min(100L, now - this.lastFrameTime) / 1000.0F;
        this.lastFrameTime = now;

        if (this.smoothedFor != entity) {
            this.smoothedFor = entity;
            this.smoothedHealth = health;
            this.smoothedAbsorption = absorption;
            this.smoothedArmor = armor;
        }
        float step = Math.clamp(delta * 10.0F, 0.0F, 1.0F);
        this.smoothedHealth += (health - this.smoothedHealth) * step;
        this.smoothedAbsorption += (absorption - this.smoothedAbsorption) * step;
        this.smoothedArmor += (armor - this.smoothedArmor) * step;
        return this.smoothedHealth;
    }

    private LivingEntity resolveTarget(Minecraft mc) {
        AuraFeature aura = AuraFeature.getMarkerFeature();
        LivingEntity current = aura != null ? aura.getCurrentTarget() : null;
        if (current == null) {
            TriggerBotFeature triggerBot = TriggerBotFeature.getEnabled();
            if (triggerBot != null) {
                current = triggerBot.getCurrentTarget();
            }
        }
        if (current == null && showcase(mc)) {
            current = mc.player;
        }
        return current != null && current.isAlive() ? current : null;
    }
}
