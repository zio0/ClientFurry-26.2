package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.AnimatedValue;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Active mob-effect list, restyled to match {@link CooldownsElement}: a header + row list with
 * the effect icon, name and remaining time per row, animating rows in/out via {@link HudRowAnimation}.
 */
public final class PotionsElement extends HudElement {
    private static final float MIN_WIDTH = 96.0F;
    private static final float HEADER_HEIGHT = 19.0F;
    private static final float BODY_PAD_TOP = 6.0F;
    private static final float BODY_PAD_BOTTOM = 6.0F;
    private static final float ROW_HEIGHT = 10.0F;
    private static final float ROW_GAP = 3.0F;
    private static final float TEXT_SIZE = 8.0F;
    private static final float TITLE_SIZE = 8.5F;
    private static final float ICON_SIZE = 9.0F;
    private static final float HEADER_ICON_SIZE = 11.5F;
    private static final float PAD_X = 7.0F;
    private static final float HEADER_RADIUS = 5.0F;
    private static final float BODY_RADIUS = 8.0F;
    private static final int HEADER_BG = ColorUtil.rgb(30, 30, 30);
    private static final int BODY_BG = ColorUtil.rgba(19, 20, 22, 179);
    private static final Identifier CHAT_PREVIEW_ID = Identifier.parse("furryhook:potions_preview");

    private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

    private final AnimatedValue visibilityAnim = new AnimatedValue();
    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue heightAnim = new AnimatedValue();
    private final Map<Identifier, RowState> rowStates = new LinkedHashMap<>();

    private List<RowVisual> entries = List.of();
    private float visible;

    public PotionsElement() {
        super("potions", "Potions");
    }

    @Override
    protected float defaultY(float unit) {
        return 260.0F * unit;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        boolean chatOpen = mc.gui.screen() instanceof ChatScreen;
        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        List<RowVisual> entries = collectEntries(mc, now, chatOpen);

        float targetWidth = MIN_WIDTH;
        for (RowVisual visual : entries) {
            PotionEntry entry = visual.entry();
            targetWidth = Math.max(targetWidth, PAD_X * 2.0F + ICON_SIZE + 3.0F
                    + font.measureWidth(entry.name(), TEXT_SIZE, 0.0F)
                    + 12.0F + font.measureWidth(entry.time(), TEXT_SIZE, 0.0F));
        }

        float rowStride = ROW_HEIGHT + ROW_GAP;
        float animatedRowsHeight = 0.0F;
        for (RowVisual visual : entries) {
            animatedRowsHeight += rowStride * visual.progress();
        }
        float listPresence = MathUtil.clamp01(animatedRowsHeight / Math.max(0.001F, rowStride));
        float bodyRowsHeight = Math.max(0.0F, animatedRowsHeight - ROW_GAP * listPresence);
        float bodyHeight = (BODY_PAD_TOP + BODY_PAD_BOTTOM) * listPresence + bodyRowsHeight;
        float targetHeight = HEADER_HEIGHT + (bodyHeight > 0.5F ? bodyHeight : 0.0F);

        boolean shouldShow = alwaysShow() || !this.rowStates.isEmpty() || (chatOpen && showcase(mc));
        this.visible = this.visibilityAnim.update(shouldShow ? 1.0F : 0.0F, 0.08F, now);
        float width = this.widthAnim.update(targetWidth, 0.09F, now);
        float height = this.heightAnim.update(targetHeight, 0.09F, now);

        this.entries = entries;
        if (this.visible <= 0.01F) {
            this.width = 0.0F;
            this.height = 0.0F;
            return;
        }
        this.width = width * unit;
        this.height = height * unit;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        if (this.visible <= 0.01F) {
            return;
        }

        float x = this.x;
        float y = this.y;
        float width = this.width;

        float panelAlpha = Math.min(1.0F, this.visible / 0.34F);
        drawPlates(x, y, width, unit, panelAlpha);

        MsdfFont font = UiFonts.sfPro(400);
        MsdfFont titleFont = UiFonts.sfPro(600);
        float headerHeight = HEADER_HEIGHT * unit;
        float titleSize = TITLE_SIZE * unit;
        float titleCenterY = y + headerHeight * 0.5F;
        Render2DUtil.text(x + PAD_X * unit, titleFont.centeredTextY(titleCenterY, titleSize), titleSize, "Potions")
                .font(titleFont)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, this.visible))
                .draw();

        float headerIconSize = HEADER_ICON_SIZE * unit;
        MsdfFont iconFont = UiFonts.divineNotificationIcons();
        Render2DUtil.text(x + width - PAD_X * unit - headerIconSize,
                        iconFont.centeredTextY(titleCenterY, headerIconSize),
                        headerIconSize, "I")
                .font(iconFont)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .draw();

        if (this.height - headerHeight <= 0.5F) {
            return;
        }

        float textSize = TEXT_SIZE * unit;
        float iconSize = ICON_SIZE * unit;
        float cursorY = y + headerHeight + BODY_PAD_TOP * unit;
        for (RowVisual rowVisual : this.entries) {
            PotionEntry entry = rowVisual.entry();
            float progress = MathUtil.clamp01(rowVisual.progress());
            float rowAlpha = this.visible * progress;
            float rowCenterY = cursorY + ROW_HEIGHT * 0.5F * unit;

            Render2DUtil.texture(x + PAD_X * unit, rowCenterY - iconSize / 2.0F, iconSize, iconSize, entry.icon())
                    .managed()
                    .color(ColorUtil.multiplyAlpha(ColorUtil.WHITE, rowAlpha))
                    .draw();
            Render2DUtil.text(x + PAD_X * unit + iconSize + 4.0F * unit, font.centeredTextY(rowCenterY, textSize), textSize, entry.name())
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, rowAlpha))
                    .draw();
            float timeWidth = font.measureWidth(entry.time(), textSize, 0.0F);
            Render2DUtil.text(x + width - PAD_X * unit - timeWidth, font.centeredTextY(rowCenterY, textSize), textSize, entry.time())
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(entry.harmful() ? Theme.Colors.SYSTEM_RED : Theme.getAccent(), rowAlpha))
                    .draw();
            cursorY += (ROW_HEIGHT + ROW_GAP) * unit * progress;
        }
    }

    /**
     * Draws the Keybinds-style twin plates: a translucent, blurred body behind a solid dark header,
     * joined into one shape, with a left accent bar on the header.
     */
    private void drawPlates(float x, float y, float width, float unit, float panelAlpha) {
        float headerHeight = HEADER_HEIGHT * unit;
        if (this.height - headerHeight > 0.5F) {
            Render2DUtil.rect(x, y, width, this.height)
                    .color(ColorUtil.multiplyAlpha(BODY_BG, panelAlpha))
                    .radius(BODY_RADIUS * unit)
                    .border(Math.max(0.5F, 0.5F * unit),
                            ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_SMALL, panelAlpha))
                    .blur(6.0F * unit, panelAlpha)
                    .draw();
        }
        Render2DUtil.rect(x, y, width, headerHeight)
                .color(ColorUtil.multiplyAlpha(HEADER_BG, panelAlpha))
                .radius(HEADER_RADIUS * unit)
                .draw();

        float barWidth = 1.3F * unit;
        float barHeight = headerHeight * 0.5F;
        Render2DUtil.rect(x, y + (headerHeight - barHeight) * 0.5F, barWidth, barHeight)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .radius(0.0F, barWidth * 0.5F, barWidth * 0.5F, 0.0F)
                .draw();
    }

    private List<RowVisual> collectEntries(Minecraft mc, long now, boolean chatOpen) {
        Set<Identifier> liveIds = new HashSet<>();
        List<RowState> liveStates = new ArrayList<>();

        if (mc.player != null) {
            for (MobEffectInstance effect : mc.player.getActiveEffects()) {
                Identifier effectId = effect.getEffect().unwrapKey().orElseThrow().identifier();
                Identifier icon = effectId.withPath(path -> "textures/mob_effect/" + path + ".png");
                boolean harmful = effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
                String name = effect.getEffect().value().getDisplayName().getString().toLowerCase(java.util.Locale.ROOT)
                        + roman(effect.getAmplifier());
                String time = isEffectivelyInfinite(effect) ? "MAX" : formatTicks(effect.getDuration());

                RowState state = this.rowStates.computeIfAbsent(effectId, RowState::new);
                state.entry = new PotionEntry(icon, name, time, harmful);
                state.seen = true;
                liveIds.add(effectId);
                liveStates.add(state);
            }
        }

        liveStates.sort(Comparator.comparing((RowState state) -> state.entry.name(), String.CASE_INSENSITIVE_ORDER));
        for (int i = 0; i < liveStates.size(); i++) {
            liveStates.get(i).targetIndex = i;
        }

        if (liveStates.isEmpty() && chatOpen && showcase(mc)) {
            RowState preview = this.rowStates.computeIfAbsent(CHAT_PREVIEW_ID, RowState::new);
            preview.entry = new PotionEntry(effectIcon("fire_resistance"), "fire resistance", "1:52", false);
            preview.seen = true;
            preview.targetIndex = 0;
            liveIds.add(CHAT_PREVIEW_ID);
        }

        List<RowVisual> visuals = new ArrayList<>();
        List<Identifier> remove = new ArrayList<>();
        for (Map.Entry<Identifier, RowState> mapEntry : this.rowStates.entrySet()) {
            RowState state = mapEntry.getValue();
            boolean seen = liveIds.contains(mapEntry.getKey());
            state.seen = seen;
            float animation = Math.max(0.0F, state.animation.update(seen, now));
            if (!seen && state.animation.isHidden()) {
                remove.add(mapEntry.getKey());
            } else if (state.entry != null && animation > 0.001F) {
                visuals.add(new RowVisual(state.entry, state.targetIndex, animation, state.id));
            }
        }
        remove.forEach(this.rowStates::remove);
        visuals.sort(Comparator.comparingInt(RowVisual::targetIndex).thenComparing(visual -> visual.id().toString()));
        return visuals;
    }

    private static String roman(int amplifier) {
        return amplifier > 0 && amplifier < ROMAN.length ? ROMAN[amplifier] : amplifier >= ROMAN.length ? " " + (amplifier + 1) : "";
    }

    // Servers often grant "permanent" buffs as a huge finite duration rather than the
    // vanilla -1 infinite sentinel, so isInfiniteDuration() misses them and the HUD prints
    // a giant "8333:19" timer. Treat anything longer than a day as infinite.
    private static final int INFINITE_THRESHOLD_TICKS = 20 * 60 * 60 * 24;

    private static boolean isEffectivelyInfinite(MobEffectInstance effect) {
        return effect.isInfiniteDuration() || effect.getDuration() > INFINITE_THRESHOLD_TICKS;
    }

    private static String formatTicks(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private static Identifier effectIcon(String name) {
        return Identifier.withDefaultNamespace("textures/mob_effect/" + name + ".png");
    }

    private static final class RowState {
        private final Identifier id;
        private final HudRowAnimation animation = new HudRowAnimation();
        private PotionEntry entry;
        private int targetIndex;
        private boolean seen;

        private RowState(Identifier id) {
            this.id = id;
        }
    }

    private record PotionEntry(Identifier icon, String name, String time, boolean harmful) {
    }

    private record RowVisual(PotionEntry entry, int targetIndex, float progress, Identifier id) {
    }
}
