package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.FeatureToggleEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import win.furryhook.utils.render.Textures;

public final class NotificationsElement extends HudElement {
    private static final long LIFETIME_MS = 4000L;
    private static final long APPEAR_MS = 200L;
    private static final long FADE_MS = 300L;
    private static final int MAX_NOTES = 5;
    private static final float STACK_GAP = 2.5F;
    private static final float TEXT_SIZE = 6.5F;
    private static final float TEXT_SEGMENT_GAP = 2.0F;
    private static final float SLIDE_PX = 6.0F;

    private static final Identifier[] CATEGORY_ICONS = {
            Textures.Icons.SWORDS,
            Textures.Icons.PERSON_STANDING,
            Textures.Icons.EYE,
            Textures.Icons.USER_ROUND,
            Textures.Icons.BOXES,
            Textures.Icons.BRAIN
    };

    private record Style(float height, float radius, float padding, float gap, float iconSize) {
    }

    private static final Style MINI = new Style(16.0F, 5.0F, 4.5F, 3.5F, 7.0F);

    // Translucent 70% backing used for the "module disabled" notification variant.
    private static final int BODY_BG = ColorUtil.rgba(19, 20, 22, 179);

    private record Segment(String text, int color, boolean gradient, boolean attach) {
        private Segment(String text, int color) {
            this(text, color, false, false);
        }

        private Segment(String text, int color, boolean gradient) {
            this(text, color, gradient, false);
        }
    }

    private record Note(Identifier icon, int iconTint, boolean managedIcon, List<Segment> segments,
                         long createdAt, float opacityDelta, String iconGlyph) {
        private Note(Identifier icon, int iconTint, boolean managedIcon, List<Segment> segments, long createdAt) {
            this(icon, iconTint, managedIcon, segments, createdAt, 0.0F, null);
        }

        private Note(Identifier icon, int iconTint, boolean managedIcon, List<Segment> segments,
                      long createdAt, float opacityDelta) {
            this(icon, iconTint, managedIcon, segments, createdAt, opacityDelta, null);
        }
    }

    private static final float DEACTIVATION_OPACITY_DELTA = 0.15F;

    private static final Deque<Note> NOTES = new ArrayDeque<>();

    private record EffectState(int duration, int amplifier, boolean expiryNotified) {
    }

    private final Map<Identifier, EffectState> trackedEffects = new HashMap<>();
    private final List<Note> visible = new ArrayList<>();
    private final Style style = MINI;

    public NotificationsElement() {
        super("notifications", "Notifications");
    }

    /**
     * Generic notification type for {@link #notify(NotificationType, String)}, mirroring
     * divine's NotificationType (SUCCESS/WARNING/INFO/MODULE) so any feature can push an
     * arbitrary toast instead of only the built-in toggle/potion sources.
     */
    public enum NotificationType {
        SUCCESS, WARNING, INFO, MODULE
    }

    /**
     * Pushes an arbitrary toast onto the notification stack. Divine's NotificationManager
     * exposes this as a general-purpose bus; furryhook previously only populated notes from
     * feature-toggle events and potion tracking.
     */
    public static void notify(NotificationType type, String message) {
        push(new Note(typeIcon(type), typeColor(type), false,
                List.of(new Segment(message, Theme.Colors.TEXT_TEXT)), System.currentTimeMillis()));
    }

    /**
     * Like {@link #notify(NotificationType, String)}, but keeps the item's real per-run
     * styling (e.g. a server-side hex-color gradient name) instead of flattening it to a
     * single plain-white string via Component#getString.
     */
    public static void notify(NotificationType type, String prefix, Component itemName) {
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(prefix, Theme.Colors.TEXT_TEXT));

        boolean[] first = {true};
        itemName.visit((style, text) -> {
            if (!text.isEmpty()) {
                TextColor textColor = style.getColor();
                int color = textColor != null ? (0xFF000000 | textColor.getValue()) : Theme.Colors.TEXT_TITLE;
                segments.add(new Segment(text, color, false, !first[0]));
                first[0] = false;
            }
            return Optional.<Void>empty();
        }, net.minecraft.network.chat.Style.EMPTY);

        push(new Note(typeIcon(type), typeColor(type), false, segments, System.currentTimeMillis()));
    }

    private static Identifier typeIcon(NotificationType type) {
        return switch (type) {
            case SUCCESS -> Textures.Hud.ICON_SUCCESS;
            case WARNING -> Textures.Hud.ICON_WARNING;
            case MODULE -> Textures.Hud.ICON_FRAME;
            case INFO -> Textures.Hud.ICON_INFO;
        };
    }

    private static int typeColor(NotificationType type) {
        return switch (type) {
            case SUCCESS -> Theme.Colors.TRAFFIC_MAXIMIZE;
            case WARNING -> Theme.Colors.SYSTEM_RED;
            case MODULE -> Theme.getAccent();
            case INFO -> Theme.Colors.TEXT_TEXT;
        };
    }

    @Override
    protected boolean centerHorizontally() {
        return true;
    }

    @Override
    protected float defaultY(float unit) {
        return 420.0F * unit;
    }

    public static final class ToggleListener {
        @EventTarget
        public void onFeatureToggle(FeatureToggleEvent event) {
            featureToggled(event.getFeature(), event.isEnabled());
        }
    }

    private static void featureToggled(Feature feature, boolean enabled) {
        Minecraft mc = MinecraftContext.mc;
        if (mc == null || mc.level == null) {
            return;
        }
        FeatureCategory category = feature.getCategory();
        push(new Note(
                categoryIcon(category),
                enabled ? Theme.getAccent() : Theme.Colors.TEXT_GHOST,
                false,
                List.of(
                        new Segment("The", Theme.Colors.TEXT_TEXT),
                        new Segment(feature.getName(), Theme.Colors.TEXT_TITLE, enabled),
                        new Segment(enabled ? "feature has been enabled" : "feature has been disabled",
                                Theme.Colors.TEXT_TEXT)
                ),
                System.currentTimeMillis(),
                enabled ? 0.0F : DEACTIVATION_OPACITY_DELTA,
                win.furryhook.utils.render.CategoryIconGlyphs.isGlyph(category)
                        ? win.furryhook.utils.render.CategoryIconGlyphs.glyph(category)
                        : null
        ));
    }

    private static void push(Note note) {
        NOTES.addLast(note);
        while (NOTES.size() > MAX_NOTES) {
            NOTES.removeFirst();
        }
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        trackEffects(mc);

        long now = System.currentTimeMillis();
        NOTES.removeIf(note -> now - note.createdAt() >= LIFETIME_MS);

        this.visible.clear();
        this.visible.addAll(NOTES);
        if (this.visible.isEmpty() && showcase(mc)) {
            this.visible.add(exampleNote(now));
        }
        if (this.visible.isEmpty()) {
            this.width = 0.0F;
            this.height = 0.0F;
            return;
        }

        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        float maxWidth = 0.0F;
        float stackHeight = 0.0F;
        for (Note note : this.visible) {
            maxWidth = Math.max(maxWidth, pillWidth(font, note, unit));
            stackHeight += (this.style.height() + STACK_GAP) * unit * envelope(note, now);
        }
        this.width = maxWidth;
        this.height = Math.max(1.0F, stackHeight - STACK_GAP * unit);
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        long now = System.currentTimeMillis();
        float guiWidth = mc.getWindow().getGuiScaledWidth();
        float pillY = this.y;
        for (Note note : this.visible) {
            float envelope = envelope(note, now);
            if (envelope > 0.01F) {
                drawPill(font, note, guiWidth, pillY, unit, now, envelope);
            }

            pillY += (this.style.height() + STACK_GAP) * unit * envelope;
        }
    }

    private static float envelope(Note note, long now) {
        long age = now - note.createdAt();
        float appear = Math.clamp(age / (float) APPEAR_MS, 0.0F, 1.0F);
        float disappear = Math.clamp((LIFETIME_MS - age) / (float) FADE_MS, 0.0F, 1.0F);
        float value = Math.min(appear, disappear);
        return value * value * (3.0F - 2.0F * value);
    }

    private void drawPill(MsdfFont font, Note note, float guiWidth, float pillY, float unit, long now, float alpha) {
        float pillHeight = this.style.height() * unit;
        float pillWidth = pillWidth(font, note, unit);

        float pillX = (guiWidth - pillWidth) / 2.0F;
        long age = now - note.createdAt();
        float appear = Math.clamp(age / (float) APPEAR_MS, 0.0F, 1.0F);
        pillY -= (1.0F - appear * appear * (3.0F - 2.0F * appear)) * SLIDE_PX * unit;

        float centerY = pillY + pillHeight / 2.0F;
        float textSize = TEXT_SIZE * unit;
        float letterSpacing = textSize * UiFontStyle.MEDIUM.letterSpacingEm();

        // A module-disable note (opacityDelta > 0) renders as a translucent, blurred pill with
        // grayed-out text; every other note stays a solid opaque pill.
        boolean muted = note.opacityDelta() > 0.001F;
        float radius = this.style.radius() * unit;
        if (muted) {
            Render2DUtil.rect(pillX, pillY, pillWidth, pillHeight)
                    .color(ColorUtil.multiplyAlpha(BODY_BG, alpha))
                    .radius(radius)
                    .border(Math.max(0.5F, 0.5F * unit), ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_SMALL, alpha))
                    .blur(6.0F * unit, alpha)
                    .draw();
        } else {
            drawPanelBacking(pillX, pillY, pillWidth, pillHeight, radius, alpha, 1.0F);
            Render2DUtil.rect(pillX, pillY, pillWidth, pillHeight)
                    .flatPanel(alpha, 0.5F * unit)
                    .radius(radius)
                    .draw();
        }

        // Left accent bar, matching the rest of the HUD (grayed for the disabled variant).
        float barWidth = 1.3F * unit;
        float barHeight = pillHeight * 0.5F;
        Render2DUtil.rect(pillX, centerY - barHeight / 2.0F, barWidth, barHeight)
                .color(ColorUtil.multiplyAlpha(muted ? Theme.Colors.TEXT_GHOST : Theme.getAccent(), alpha))
                .radius(0.0F, barWidth * 0.5F, barWidth * 0.5F, 0.0F)
                .draw();

        float iconSize = this.style.iconSize() * unit;
        float gap = this.style.gap() * unit;
        float cursor = pillX + this.style.padding() * unit;

        if (note.iconGlyph() != null) {
            MsdfFont iconFont = UiFonts.divineNotificationIcons();
            Render2DUtil.text(cursor, iconFont.centeredTextY(centerY, iconSize), iconSize, note.iconGlyph())
                    .font(iconFont)
                    .color(ColorUtil.multiplyAlpha(note.iconTint(), alpha))
                    .draw();
        } else {
            var icon = Render2DUtil.texture(cursor, centerY - iconSize / 2.0F, iconSize, iconSize, note.icon());
            if (note.managedIcon()) {
                icon.managed();
                icon.color(ColorUtil.multiplyAlpha(ColorUtil.WHITE, alpha));
            } else {
                icon.color(ColorUtil.multiplyAlpha(note.iconTint(), alpha));
            }
            icon.draw();
        }
        cursor += iconSize + gap;

        Render2DUtil.rect(cursor, centerY - iconSize / 2.0F, Math.max(0.5F, 0.5F * unit), iconSize)
                .color(ColorUtil.multiplyAlpha(DIVIDER_COLOR, alpha))
                .draw();
        cursor += Math.max(0.5F, 0.5F * unit) + gap;

        float textY = font.centeredTextY(centerY, textSize);
        for (int index = 0; index < note.segments().size(); index++) {
            Segment segment = note.segments().get(index);
            if (index > 0 && !segment.attach()) {
                cursor += TEXT_SEGMENT_GAP * unit;
            }
            if (segment.gradient() && !muted) {
                cursor = drawGradientSegment(font, segment.text(), cursor, textY, textSize, letterSpacing, alpha);
            } else {
                int segmentColor = muted ? Theme.Colors.SECONDARY : segment.color();
                Render2DUtil.text(cursor, textY, textSize, segment.text())
                        .style(UiFontStyle.MEDIUM)
                        .color(ColorUtil.multiplyAlpha(segmentColor, alpha))
                        .draw();
                cursor += font.measureWidth(segment.text(), textSize, letterSpacing);
            }
        }
    }

    /**
     * Per-glyph shimmering highlight ported from divine's module-name gradient
     * (LogoHudElement's brand text / NotificationsHudElement's module message).
     */
    private static float drawGradientSegment(MsdfFont font, String text, float startX, float textY,
                                              float textSize, float letterSpacing, float alpha) {
        float cursor = startX;
        for (int i = 0; i < text.length(); i++) {
            String glyph = String.valueOf(text.charAt(i));
            int color = ColorUtil.fade(7, i * 22, Theme.getAccent(), ColorUtil.WHITE);
            Render2DUtil.text(cursor, textY, textSize, glyph)
                    .style(UiFontStyle.MEDIUM)
                    .color(ColorUtil.multiplyAlpha(color, alpha))
                    .draw();
            cursor += font.measureWidth(glyph, textSize, letterSpacing);
        }
        return cursor;
    }

    private float pillWidth(MsdfFont font, Note note, float unit) {
        float textSize = TEXT_SIZE * unit;
        float letterSpacing = textSize * UiFontStyle.MEDIUM.letterSpacingEm();
        float textWidth = 0.0F;
        int gaps = 0;
        for (int index = 0; index < note.segments().size(); index++) {
            Segment segment = note.segments().get(index);
            textWidth += font.measureWidth(segment.text(), textSize, letterSpacing);
            if (index > 0 && !segment.attach()) {
                gaps++;
            }
        }
        textWidth += gaps * TEXT_SEGMENT_GAP * unit;
        return (this.style.padding() * 2.0F + this.style.iconSize() + this.style.gap() * 2.0F) * unit
                + Math.max(0.5F, 0.5F * unit)
                + textWidth;
    }

    private void trackEffects(Minecraft mc) {
        if (mc.player == null) {
            this.trackedEffects.clear();
            return;
        }

        Map<Identifier, EffectState> current = new HashMap<>();
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            Identifier effectId = effect.getEffect().unwrapKey().orElseThrow().identifier();
            int duration = effect.getDuration();
            int amplifier = effect.getAmplifier();
            EffectState previous = this.trackedEffects.get(effectId);

            // Applied/expiry buff notifications intentionally suppressed — noise the caller
            // does not want in the feed. State is still tracked so a future re-enable can
            // pick up where it left off without spamming everything currently active.
            boolean expiryNotified = previous != null && previous.expiryNotified();
            current.put(effectId, new EffectState(duration, amplifier, expiryNotified));
        }
        this.trackedEffects.clear();
        this.trackedEffects.putAll(current);
    }

private Note exampleNote(long now) {
        return new Note(categoryIcon(FeatureCategory.VISUAL), Theme.getAccent(), false, List.of(
                new Segment("The", Theme.Colors.TEXT_TEXT),
                new Segment("ExampleFeature", Theme.Colors.TEXT_TITLE),
                new Segment("feature has been enabled", Theme.Colors.TEXT_TEXT)
        ), now - LIFETIME_MS / 2L);
    }

    private static Identifier categoryIcon(FeatureCategory category) {
        int index = category.ordinal();
        return CATEGORY_ICONS[index >= 0 && index < CATEGORY_ICONS.length ? index : 0];
    }
}
