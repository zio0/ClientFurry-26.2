package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import win.furryhook.event.EventManager;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.lifecycle.FeatureToggleEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Bound-feature list, reskinned to match {@link CooldownsElement}/{@link PotionsElement}/
 * {@link StaffListElement}: header with a texture accent icon and hairline divider, rows that
 * bounce in/out via {@link HudRowAnimation} as features gain/lose a visible bind, and a smooth
 * fade/collapse when the panel has nothing to show. Only lists currently-enabled features with a
 * visible bind (mirrors {@link PotionsElement} only showing active effects), and reuses the same
 * per-category icon set as the module list GUI and {@link NotificationsElement}.
 */
public final class KeybindsElement extends HudElement {
    private static final float MIN_WIDTH = 96.0F;
    private static final float HEADER_HEIGHT = 19.0F;
    private static final float BODY_PAD_TOP = 6.0F;
    private static final float BODY_PAD_BOTTOM = 6.0F;
    private static final float ROW_HEIGHT = 10.0F;
    private static final float ROW_GAP = 3.0F;
    private static final float TEXT_SIZE = 8.0F;
    private static final float TITLE_SIZE = 8.5F;
    private static final float ICON_SIZE = 11.5F;
    private static final float PAD_X = 7.0F;
    private static final float HEADER_RADIUS = 5.0F;
    private static final float BODY_RADIUS = 8.0F;

    private static final int HEADER_BG = ColorUtil.rgb(30, 30, 30);
    private static final int BODY_BG = ColorUtil.rgba(19, 20, 22, 179);

    private record BoundEntry(String name, String bind, FeatureCategory category) {
    }

    private final Map<String, RowState> rowStates = new HashMap<>();
    private final AnimatedValue visibilityAnim = new AnimatedValue();
    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue heightAnim = new AnimatedValue();
    private List<RowVisual> visuals = List.of();
    private float visible;

    private boolean rowsDirty = true;
    private int lastFingerprint;

    public KeybindsElement() {
        super("keybinds", "Keybinds");
        EventManager.subscribe(this);
    }

    @EventTarget
    public void onFeatureToggle(FeatureToggleEvent event) {
        this.rowsDirty = true;
    }

    @Override
    protected float defaultY(float unit) {
        return 42.0F * unit;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        int fingerprint = fingerprint(mc);
        if (this.rowsDirty || fingerprint != this.lastFingerprint) {
            this.rowsDirty = false;
            this.lastFingerprint = fingerprint;
        }

        List<BoundEntry> entries = collectEntries(mc);
        for (RowState state : this.rowStates.values()) {
            state.seen = false;
        }
        for (int i = 0; i < entries.size(); i++) {
            BoundEntry entry = entries.get(i);
            RowState state = this.rowStates.get(entry.name());
            if (state == null) {
                state = new RowState(entry);
                this.rowStates.put(entry.name(), state);
            } else {
                state.entry = entry;
            }
            state.seen = true;
            state.targetIndex = i;
        }

        MsdfFont font = UiFonts.sfPro(400);
        MsdfFont boldFont = UiFonts.sfPro(600);
        float maxLabelW = 0.0F;
        float maxKeyW = 0.0F;
        for (RowState state : this.rowStates.values()) {
            maxLabelW = Math.max(maxLabelW, font.measureWidth(state.entry.name(), TEXT_SIZE, 0.0F));
            maxKeyW = Math.max(maxKeyW, font.measureWidth(state.entry.bind(), TEXT_SIZE, 0.0F));
        }
        float headerWidth = PAD_X * 2.0F
                + boldFont.measureWidth("Keybinds", TITLE_SIZE, 0.0F)
                + 10.0F + ICON_SIZE;
        float rowsWidth = PAD_X * 2.0F + maxLabelW + 12.0F + maxKeyW;
        float targetWidth = Math.max(MIN_WIDTH, Math.max(headerWidth, rowsWidth));

        List<RowVisual> visuals = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RowState> stateEntry : this.rowStates.entrySet()) {
            RowState state = stateEntry.getValue();
            float progress = Math.max(0.0F, state.animation.update(state.seen, now));
            if (!state.seen && state.animation.isHidden()) {
                toRemove.add(stateEntry.getKey());
                continue;
            }
            if (progress > 0.001F) {
                visuals.add(new RowVisual(state.entry, state.targetIndex, progress));
            }
        }
        toRemove.forEach(this.rowStates::remove);
        visuals.sort(Comparator.comparingInt(RowVisual::targetIndex)
                .thenComparing(visual -> visual.entry().name(), String.CASE_INSENSITIVE_ORDER));
        this.visuals = visuals;

        float rowStride = ROW_HEIGHT + ROW_GAP;
        float animatedRowsHeight = 0.0F;
        for (RowVisual visual : visuals) {
            animatedRowsHeight += rowStride * visual.progress();
        }
        float listPresence = MathUtil.clamp01(animatedRowsHeight / Math.max(0.001F, rowStride));
        float bodyRowsHeight = Math.max(0.0F, animatedRowsHeight - ROW_GAP * listPresence);
        float bodyHeight = (BODY_PAD_TOP + BODY_PAD_BOTTOM) * listPresence + bodyRowsHeight;
        float targetHeight = HEADER_HEIGHT + (bodyHeight > 0.5F ? bodyHeight : 0.0F);

        boolean shouldShow = alwaysShow() || !this.rowStates.isEmpty() || showcase(mc);
        this.visible = this.visibilityAnim.update(shouldShow ? 1.0F : 0.0F, 0.08F, now);
        float width = this.widthAnim.update(targetWidth, 0.09F, now);
        float height = this.heightAnim.update(targetHeight, 0.09F, now);

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
        float headerHeight = HEADER_HEIGHT * unit;

        boolean hasBody = this.height - headerHeight > 0.5F;

        // ---- Body plate: translucent 70%, blurred - drawn full-height BEHIND the header so
        // the two plates read as one joined shape with no background showing between them. ----
        if (hasBody) {
            Render2DUtil.rect(x, y, width, this.height)
                    .color(ColorUtil.multiplyAlpha(BODY_BG, panelAlpha))
                    .radius(BODY_RADIUS * unit)
                    .border(Math.max(0.5F, 0.5F * unit),
                            ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_SMALL, panelAlpha))
                    .blur(6.0F * unit, panelAlpha)
                    .draw();
        }

        // ---- Header plate: solid #1E1E1E, sits on top of the body ----
        Render2DUtil.rect(x, y, width, headerHeight)
                .color(ColorUtil.multiplyAlpha(HEADER_BG, panelAlpha))
                .radius(HEADER_RADIUS * unit)
                .draw();

        // Left accent bar - flush against the header's left edge, on top of the plate.
        float barWidth = 1.3F * unit;
        float barHeight = headerHeight * 0.5F;
        Render2DUtil.rect(x, y + (headerHeight - barHeight) * 0.5F, barWidth, barHeight)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .radius(0.0F, barWidth * 0.5F, barWidth * 0.5F, 0.0F)
                .draw();

        MsdfFont boldFont = UiFonts.sfPro(600);
        MsdfFont font = UiFonts.sfPro(400);

        float titleSize = TITLE_SIZE * unit;
        float titleCenterY = y + headerHeight * 0.5F;
        Render2DUtil.text(x + PAD_X * unit, boldFont.centeredTextY(titleCenterY, titleSize), titleSize, "Keybinds")
                .font(boldFont)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, this.visible))
                .draw();

        float headerIconSize = ICON_SIZE * unit;
        MsdfFont iconFont = UiFonts.divineNotificationIcons();
        Render2DUtil.text(x + width - PAD_X * unit - headerIconSize,
                        iconFont.centeredTextY(titleCenterY, headerIconSize),
                        headerIconSize, "J")
                .font(iconFont)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .draw();

        if (!hasBody) {
            return;
        }

        // ---- Rows: name (regular, left) + bind (accent, right) ----
        float textSize = TEXT_SIZE * unit;
        float cursorY = y + headerHeight + BODY_PAD_TOP * unit;
        for (RowVisual visual : this.visuals) {
            BoundEntry entry = visual.entry();
            float progress = MathUtil.clamp01(visual.progress());
            float rowAlpha = this.visible * progress;
            float rowCenterY = cursorY + ROW_HEIGHT * 0.5F * unit;

            Render2DUtil.text(x + PAD_X * unit, font.centeredTextY(rowCenterY, textSize), textSize, entry.name())
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, rowAlpha))
                    .draw();

            String bindText = entry.bind();
            float bindWidth = font.measureWidth(bindText, textSize, 0.0F);
            Render2DUtil.text(x + width - PAD_X * unit - bindWidth, font.centeredTextY(rowCenterY, textSize), textSize, bindText)
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(Theme.getAccent(), rowAlpha))
                    .draw();

            cursorY += (ROW_HEIGHT + ROW_GAP) * unit * progress;
        }
    }

    private int fingerprint(Minecraft mc) {
        int fp = showcase(mc) ? 1 : 0;
        for (Feature feature : FeatureManager.INSTANCE.getFeatures()) {
            fp = fp * 31 + feature.getName().hashCode();
            List<Integer> bindCodes = feature.getBinds();
            fp = fp * 31 + bindCodes.hashCode();
            for (int i = 0; i < bindCodes.size(); i++) {
                fp = fp * 31 + (feature.isBindVisibleAt(i) ? 1 : 0);
            }
            fp = fp * 31 + (feature.isEnabled() ? 1 : 0);
        }
        return fp;
    }

    private List<BoundEntry> collectEntries(Minecraft mc) {
        List<BoundEntry> entries = new ArrayList<>();
        for (Feature feature : FeatureManager.INSTANCE.getFeatures()) {
            StringJoiner binds = new StringJoiner(" + ");
            List<Integer> bindCodes = feature.getBinds();
            for (int i = 0; i < bindCodes.size(); i++) {
                int bindCode = bindCodes.get(i);
                if (bindCode != BindSetting.UNBOUND && feature.isBindVisibleAt(i)) {
                    binds.add(BindSetting.describe(bindCode));
                }
            }
            if (binds.length() > 0 && feature.isEnabled()) {
                entries.add(new BoundEntry(displayName(feature.getName()), binds.toString(), feature.getCategory()));
            }
        }
        return entries;
    }

    private static String displayName(String name) {
        return name.length() <= 14 ? name : name.substring(0, 13) + "…";
    }

    private static final class RowState {
        private BoundEntry entry;
        private final HudRowAnimation animation = new HudRowAnimation();
        private boolean seen;
        private int targetIndex;

        private RowState(BoundEntry entry) {
            this.entry = entry;
        }
    }

    private record RowVisual(BoundEntry entry, int targetIndex, float progress) {
    }
}
