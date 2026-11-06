package win.furryhook.feature.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.hud.BottomStatsElement;
import win.furryhook.hud.CooldownsElement;
import win.furryhook.hud.HudElement;
import win.furryhook.hud.HudElementSettingsPopup;
import win.furryhook.hud.KeybindsElement;
import win.furryhook.hud.NotificationsElement;
import win.furryhook.hud.PotionsElement;
import win.furryhook.hud.PveStatusElement;
import win.furryhook.hud.QuickUseElement;
import win.furryhook.hud.StaffListElement;
import win.furryhook.hud.TargetElement;
import win.furryhook.hud.TpsElement;
import win.furryhook.hud.WatermarkElement;
import win.furryhook.feature.impl.pve.PveManagerFeature;
import win.furryhook.menu.core.MenuConfigStore;
import win.furryhook.menu.i18n.MenuText;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class HudFeature extends Feature {

    // 100% used to render what's now the 153% look; rebased so 100% = the old 153% baseline.
    private static final float HUD_SCALE = 1.4F * 1.53F;
    private static final String SIZE_MIGRATION_KEY = "hudScale100Migrated";
    private static final double LEGACY_DEFAULT_SIZE = 70.0;
    private static final String SIZE_REBASE_KEY = "hudScale153Rebased";
    private static final double PRE_REBASE_SIZE = 153.0;
    private static final Identifier RESET_ICON = Identifier.parse("furryhook:textures/menu/icons/refresh_ccw.svg");
    private static final int RESET_DIVIDER_COLOR = ColorUtil.rgba(255, 255, 255, 25);
    private static final String RESET_LABEL = "Сбросить расположение";
    private static final float SNAP_MARGIN = 12.0F;
    private static final float SNAP_GAP = 6.0F;
    private static final float SNAP_THRESHOLD = 8.0F;

    private static final String WATERMARK = "Watermark";
    private static final String KEYBINDS = "Keybinds";
    private static final String POTIONS = "Potions";
    private static final String TARGET = "Target";
    private static final String NOTIFICATIONS = "Notifications";
    private static final String INFORMATION = "Information";
    private static final String QUICK_USE = "QuickUse";
    private static final String COOLDOWNS = "Cooldowns";
    private static final String STAFF_LIST = "StaffList";
    private static final String PVE_STATE = "PveState";

    public final MultiSelectSetting elements = register(new MultiSelectSetting(
            "Elements",
            List.of(WATERMARK, KEYBINDS, POTIONS, TARGET, NOTIFICATIONS, INFORMATION, QUICK_USE, COOLDOWNS, STAFF_LIST),
            WATERMARK, KEYBINDS, POTIONS, TARGET, NOTIFICATIONS, INFORMATION, QUICK_USE, COOLDOWNS, STAFF_LIST,
            // Deliberately absent from the defaults above: this panel used to be driven by a
            // "Current State" flag buried in PveManager, so it appeared over the game with no way
            // to switch it off from where every other HUD element is switched off. Now it is one
            // of them, and it starts off.
            PVE_STATE
    ));
    public final ModeSetting targetStyle = register(new ModeSetting(
            "Target Style",
            TargetElement.STYLE_DEFAULT,
            TargetElement.STYLE_DEFAULT,
            TargetElement.STYLE_GSMAR
    ).visibleWhen(() -> this.elements.isSelected(TARGET)));
    /** Burst of sparks off the target's head every time it takes a hit - both target styles. */
    public final BooleanSetting targetHeadParticles = register(new BooleanSetting("Head Particles", true)
            .visibleWhen(() -> this.elements.isSelected(TARGET)));
    /** Sparks shed off the health bar as the target's health drains - default style only. */
    public final BooleanSetting targetHealthParticles = register(new BooleanSetting("Health Particles", true)
            .visibleWhen(() -> this.elements.isSelected(TARGET)
                    && this.targetStyle.is(TargetElement.STYLE_DEFAULT)));
    public final NumberSetting size = register(new NumberSetting("Size", 100.0, 50.0, 200.0, 1.0, "%"));
    // Hidden from the main module settings list - configured from the watermark's own
    // right-click popup instead (still registered here so the values persist).
    public final MultiSelectSetting watermarkContent = register(new MultiSelectSetting(
            "Watermark Content",
            List.of(WatermarkElement.CONTENT_ITEMS),
            WatermarkElement.CONTENT_ITEMS
    )).visibleWhen(() -> false);
    // Displays as "Information Content" in the module list - what to show across the bottom-left
    // coordinates/BPS pill and the bottom-right TPS pill.
    private static final List<String> INFORMATION_CONTENT_ITEMS = List.of(
            BottomStatsElement.ITEM_COORDINATES, BottomStatsElement.ITEM_BPS, TpsElement.ITEM_TPS
    );
    public final MultiSelectSetting informationContent = register(new MultiSelectSetting(
            "Information Content",
            INFORMATION_CONTENT_ITEMS,
            INFORMATION_CONTENT_ITEMS.toArray(new String[0])
    ).visibleWhen(() -> this.elements.isSelected(INFORMATION)));

    private static HudFeature instance;
    private final WatermarkElement watermarkElement = new WatermarkElement();
    private final KeybindsElement keybindsElement = new KeybindsElement();
    private final PotionsElement potionsElement = new PotionsElement();
    private final TargetElement targetElement = new TargetElement();
    private final NotificationsElement notificationsElement = new NotificationsElement();
    private final BottomStatsElement bottomStatsElement = new BottomStatsElement();
    private final TpsElement tpsElement = new TpsElement();
    private final QuickUseElement quickUseElement = new QuickUseElement();
    private final PveStatusElement pveStatusElement = new PveStatusElement();
    private final CooldownsElement cooldownsElement = new CooldownsElement();
    private final StaffListElement staffListElement = new StaffListElement();
    private final HudElementSettingsPopup settingsPopup = new HudElementSettingsPopup();
    private final List<HudElement> configurableElements = List.of(
            this.cooldownsElement, this.staffListElement, this.keybindsElement, this.potionsElement, this.watermarkElement
    );
    private final List<HudElement> allElements = List.of(
            this.watermarkElement,
            this.keybindsElement,
            this.potionsElement,
            this.targetElement,
            this.notificationsElement,
            this.bottomStatsElement,
            this.tpsElement,
            this.quickUseElement,
            this.pveStatusElement,
            this.cooldownsElement,
            this.staffListElement
    );
    private HudElement draggedElement;
    private boolean sizeMigrationChecked;
    private boolean sizeRebaseChecked;
    private float resetButtonX;
    private float resetButtonY;
    private float resetButtonWidth;
    private float resetButtonHeight;
    private float activeGuideX = Float.NaN;
    private float activeGuideY = Float.NaN;

    private record SnapCandidate(float position, float guide) {
    }

    public HudFeature() {
        super("HUD", "HUD customization settings", FeatureCategory.VISUAL, BindSetting.UNBOUND);
        instance = this;
    }

    public static float backgroundOpacity() {
        return 1.0F;
    }

    /** True while the target element should render the "Gsmar" style instead of the default one. */
    public static boolean gsmarTarget() {
        return instance != null && instance.targetStyle.is(TargetElement.STYLE_GSMAR);
    }

    /** True while the target element should spark off the target's head on every hit. */
    public static boolean targetHeadParticles() {
        return instance != null && instance.targetHeadParticles.getValue();
    }

    /** True while the default target style should shed particles as the health bar drains. */
    public static boolean targetHealthParticles() {
        return instance != null && instance.targetHealthParticles.getValue();
    }

    public static boolean glareEnabled() {
        return false;
    }

    public static boolean watermarkShows(String item) {
        return instance != null && instance.watermarkContent.isSelected(item);
    }

    public static boolean informationShows(String item) {
        return instance != null && instance.informationContent.isSelected(item);
    }

    /**
     * Vertical space the boss bar should be pushed down by so it doesn't overlap the watermark -
     * the watermark is freely draggable now, so this only kicks in while it actually sits near
     * the top-center where the vanilla boss bar renders.
     */
    public static float bossBarYOffset() {
        if (instance == null || !instance.isEnabled() || !instance.elements.isSelected(WATERMARK)) {
            return 0.0F;
        }
        Minecraft mc = MinecraftContext.mc;
        if (mc == null) {
            return 0.0F;
        }
        float height = instance.watermarkElement.height();
        if (height <= 0.5F) {
            return 0.0F;
        }
        float screenCenterX = mc.getWindow().getGuiScaledWidth() / 2.0F;
        float watermarkCenterX = instance.watermarkElement.x() + instance.watermarkElement.width() / 2.0F;
        boolean nearTop = instance.watermarkElement.y() < 60.0F;
        boolean nearCenterX = Math.abs(watermarkCenterX - screenCenterX) < instance.watermarkElement.width() / 2.0F + 40.0F;
        return nearTop && nearCenterX ? height + 16.0F : 0.0F;
    }

    public static void toggleWatermarkContent(String item) {
        if (instance != null) {
            instance.watermarkContent.toggle(item);
        }
    }

    public static void toggleInformationContent(String item) {
        if (instance != null) {
            instance.informationContent.toggle(item);
        }
    }

    @EventTarget(priority = -100)
    public void onRender2D(Render2DEvent event) {
        Minecraft mc = event.getClient();
        if (mc == null || mc.player == null) {
            return;
        }

        migrateLegacySize();
        rebaseSizeTo153Baseline();

        float unit = unit(mc);
        List<HudElement> visible = visibleElements();
        boolean chatOpen = mc.gui.screen() instanceof ChatScreen;
        boolean snapMode = chatOpen && controlDown(mc);

        if (this.draggedElement != null) {
            if (chatOpen) {
                this.draggedElement.dragTo(mouseX(mc), mouseY(mc));
                if (snapMode) {
                    applySnap(mc, visible);
                } else {
                    clearSnapGuides();
                }
            } else {
                stopDrag(mc);
            }
        } else {
            clearSnapGuides();
        }
        if (this.settingsPopup.isOpen() && !chatOpen) {
            this.settingsPopup.close();
        }

        if (snapMode) {
            drawSnapGrid(mc);
        }
        for (HudElement element : visible) {
            element.render(mc, unit);
        }
        if (snapMode) {
            drawResetButton(mc, unit, visible);
        } else {
            this.resetButtonWidth = 0.0F;
            this.resetButtonHeight = 0.0F;
        }
        this.settingsPopup.render(mc, unit);
    }

    private float unit(Minecraft mc) {
        return (float) (HUD_SCALE / mc.getWindow().getGuiScale() * this.size.getValue() / 100.0);
    }

    private String popupTitle(HudElement element) {
        if (element == this.cooldownsElement) {
            return MenuText.ui("Cooldowns");
        }
        if (element == this.staffListElement) {
            return MenuText.ui("Staff list");
        }
        if (element == this.keybindsElement) {
            return MenuText.ui("Keybinds");
        }
        if (element == this.potionsElement) {
            return MenuText.ui("Potions");
        }
        return element.displayName();
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        Minecraft mc = MinecraftContext.mc;
        if (mc == null) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.getAction() == GLFW.GLFW_RELEASE) {
            if (this.draggedElement != null) {
                stopDrag(mc);
                event.cancel();
            }
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS || !(mc.gui.screen() instanceof ChatScreen)) {
            return;
        }

        float mouseX = mouseX(mc);
        float mouseY = mouseY(mc);
        float unit = unit(mc);

        if (this.settingsPopup.isOpen()) {
            if (this.settingsPopup.contains(mouseX, mouseY, unit)) {
                if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    this.settingsPopup.handleClick(mouseX, mouseY, unit);
                }
                event.cancel();
                return;
            }
            this.settingsPopup.close();
        }

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            List<HudElement> visible = visibleElements();
            for (HudElement element : this.configurableElements) {
                if (!visible.contains(element) || !element.contains(mouseX, mouseY)) {
                    continue;
                }
                this.settingsPopup.open(
                        element,
                        popupTitle(element),
                        element.x(),
                        element.y() + element.height() + 4.0F * unit,
                        mc.getWindow().getGuiScaledWidth(),
                        mc.getWindow().getGuiScaledHeight(),
                        unit
                );
                event.cancel();
                return;
            }
            return;
        }

        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        if (controlDown(mc) && inside(mouseX, mouseY,
                this.resetButtonX,
                this.resetButtonY,
                this.resetButtonWidth,
                this.resetButtonHeight)) {
            resetPositions();
            event.cancel();
            return;
        }
        if (inside(mouseX, mouseY,
                mc.getWindow().getGuiScaledWidth() - 150.0F,
                mc.getWindow().getGuiScaledHeight() - 40.0F,
                146.0F,
                20.0F)) {
            return;
        }
        for (HudElement element : visibleElements()) {
            if (element.startDrag(mouseX, mouseY)) {
                this.draggedElement = element;
                event.cancel();
                return;
            }
        }
    }

    @Override
    protected void onDisable() {
        if (this.draggedElement != null && MinecraftContext.mc != null) {
            stopDrag(MinecraftContext.mc);
        }
    }

    public void resetPositions() {
        this.draggedElement = null;
        clearSnapGuides();
        MenuConfigStore.resetHudPositions();
        this.allElements.forEach(HudElement::resetPosition);
    }

    private void applySnap(Minecraft mc, List<HudElement> visible) {
        HudElement dragged = this.draggedElement;
        if (dragged == null) {
            clearSnapGuides();
            return;
        }

        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float maxX = Math.max(0.0F, screenWidth - dragged.width());
        float maxY = Math.max(0.0F, screenHeight - dragged.height());
        List<SnapCandidate> xCandidates = new ArrayList<>(20);
        List<SnapCandidate> yCandidates = new ArrayList<>(20);

        addCandidate(xCandidates, Math.min(SNAP_MARGIN, maxX), SNAP_MARGIN, maxX);
        addCandidate(xCandidates, maxX / 2.0F, screenWidth / 2.0F, maxX);
        addCandidate(xCandidates, Math.max(0.0F, maxX - SNAP_MARGIN), screenWidth - SNAP_MARGIN, maxX);
        addCandidate(yCandidates, Math.min(SNAP_MARGIN, maxY), SNAP_MARGIN, maxY);
        addCandidate(yCandidates, maxY / 2.0F, screenHeight / 2.0F, maxY);
        addCandidate(yCandidates, Math.max(0.0F, maxY - SNAP_MARGIN), screenHeight - SNAP_MARGIN, maxY);

        for (HudElement other : visible) {
            if (other == dragged || other.width() <= 0.5F || other.height() <= 0.5F) {
                continue;
            }

            float otherRight = other.x() + other.width();
            float otherBottom = other.y() + other.height();
            float otherCenterX = other.x() + other.width() / 2.0F;
            float otherCenterY = other.y() + other.height() / 2.0F;

            addCandidate(xCandidates, other.x(), other.x(), maxX);
            addCandidate(xCandidates, otherRight - dragged.width(), otherRight, maxX);
            addCandidate(xCandidates, otherCenterX - dragged.width() / 2.0F, otherCenterX, maxX);
            addCandidate(xCandidates, other.x() - dragged.width() - SNAP_GAP, other.x() - SNAP_GAP / 2.0F, maxX);
            addCandidate(xCandidates, otherRight + SNAP_GAP, otherRight + SNAP_GAP / 2.0F, maxX);

            addCandidate(yCandidates, other.y(), other.y(), maxY);
            addCandidate(yCandidates, otherBottom - dragged.height(), otherBottom, maxY);
            addCandidate(yCandidates, otherCenterY - dragged.height() / 2.0F, otherCenterY, maxY);
            addCandidate(yCandidates, other.y() - dragged.height() - SNAP_GAP, other.y() - SNAP_GAP / 2.0F, maxY);
            addCandidate(yCandidates, otherBottom + SNAP_GAP, otherBottom + SNAP_GAP / 2.0F, maxY);
        }

        float x = Math.clamp(dragged.x(), 0.0F, maxX);
        float y = Math.clamp(dragged.y(), 0.0F, maxY);
        this.activeGuideX = Float.NaN;
        this.activeGuideY = Float.NaN;

        if (!dragged.isHorizontallyCentered()) {
            SnapCandidate snappedX = nearestCandidate(x, xCandidates);
            if (snappedX != null) {
                x = snappedX.position();
                this.activeGuideX = snappedX.guide();
            }
        }
        SnapCandidate snappedY = nearestCandidate(y, yCandidates);
        if (snappedY != null) {
            y = snappedY.position();
            this.activeGuideY = snappedY.guide();
        }
        dragged.snapTo(x, y);
    }

    private static void addCandidate(List<SnapCandidate> candidates, float position, float guide, float maximum) {
        if (Float.isFinite(position) && position >= 0.0F && position <= maximum) {
            candidates.add(new SnapCandidate(position, guide));
        }
    }

    private static SnapCandidate nearestCandidate(float position, List<SnapCandidate> candidates) {
        SnapCandidate nearest = null;
        float nearestDistance = SNAP_THRESHOLD;
        for (SnapCandidate candidate : candidates) {
            float distance = Math.abs(position - candidate.position());
            if (distance <= nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void drawSnapGrid(Minecraft mc) {
        float width = mc.getWindow().getGuiScaledWidth();
        float height = mc.getWindow().getGuiScaledHeight();
        float line = (float) Math.max(0.5, 1.0 / Math.max(1.0, mc.getWindow().getGuiScale()));
        int thirdsColor = ColorUtil.rgba(190, 195, 205, 36);
        int centerColor = ColorUtil.rgba(205, 209, 216, 58);
        int cornerColor = ColorUtil.rgba(210, 214, 220, 78);
        float left = SNAP_MARGIN;
        float top = SNAP_MARGIN;
        float right = width - SNAP_MARGIN;
        float bottom = height - SNAP_MARGIN;
        float cornerLength = 10.0F;

        drawDashedVertical(width / 3.0F, top, bottom, 1.5F, 6.0F, line, thirdsColor);
        drawDashedVertical(width * 2.0F / 3.0F, top, bottom, 1.5F, 6.0F, line, thirdsColor);
        drawDashedHorizontal(height / 3.0F, left, right, 1.5F, 6.0F, line, thirdsColor);
        drawDashedHorizontal(height * 2.0F / 3.0F, left, right, 1.5F, 6.0F, line, thirdsColor);
        drawDashedVertical(width / 2.0F, top, bottom, 4.0F, 6.0F, line, centerColor);
        drawDashedHorizontal(height / 2.0F, left, right, 4.0F, 6.0F, line, centerColor);

        Render2DUtil.rect(left, top, cornerLength, line).color(cornerColor).draw();
        Render2DUtil.rect(left, top, line, cornerLength).color(cornerColor).draw();
        Render2DUtil.rect(right - cornerLength, top, cornerLength, line).color(cornerColor).draw();
        Render2DUtil.rect(right - line, top, line, cornerLength).color(cornerColor).draw();
        Render2DUtil.rect(left, bottom - line, cornerLength, line).color(cornerColor).draw();
        Render2DUtil.rect(left, bottom - cornerLength, line, cornerLength).color(cornerColor).draw();
        Render2DUtil.rect(right - cornerLength, bottom - line, cornerLength, line).color(cornerColor).draw();
        Render2DUtil.rect(right - line, bottom - cornerLength, line, cornerLength).color(cornerColor).draw();

        if (Float.isFinite(this.activeGuideX)) {
            Render2DUtil.rect(this.activeGuideX - line * 2.0F, 0.0F, line * 4.0F, height)
                    .color(ColorUtil.rgba(205, 210, 218, 30))
                    .draw();
            Render2DUtil.rect(this.activeGuideX - line / 2.0F, 0.0F, line, height)
                    .color(ColorUtil.rgba(220, 224, 230, 175))
                    .draw();
        }
        if (Float.isFinite(this.activeGuideY)) {
            Render2DUtil.rect(0.0F, this.activeGuideY - line * 2.0F, width, line * 4.0F)
                    .color(ColorUtil.rgba(205, 210, 218, 30))
                    .draw();
            Render2DUtil.rect(0.0F, this.activeGuideY - line / 2.0F, width, line)
                    .color(ColorUtil.rgba(220, 224, 230, 175))
                    .draw();
        }
    }

    private static void drawDashedVertical(float x, float top, float bottom, float dash, float gap, float width, int color) {
        for (float y = top; y < bottom; y += dash + gap) {
            Render2DUtil.rect(x - width / 2.0F, y, width, Math.min(dash, bottom - y)).color(color).draw();
        }
    }

    private static void drawDashedHorizontal(float y, float left, float right, float dash, float gap, float height, int color) {
        for (float x = left; x < right; x += dash + gap) {
            Render2DUtil.rect(x, y - height / 2.0F, Math.min(dash, right - x), height).color(color).draw();
        }
    }

    private void clearSnapGuides() {
        this.activeGuideX = Float.NaN;
        this.activeGuideY = Float.NaN;
    }

    private void drawResetButton(Minecraft mc, float unit, List<HudElement> visible) {
        float height = 28.0F * unit;
        float radius = 8.0F * unit;
        float padding = 8.0F * unit;
        float gap = 4.0F * unit;
        float iconSize = 12.0F * unit;
        float dividerWidth = Math.max(0.5F, 0.5F * unit);
        float textSize = 10.0F * unit;
        MsdfFont font = UiFonts.sfProDisplay();
        float letterSpacing = textSize * UiFontStyle.MEDIUM.letterSpacingEm();
        float textWidth = font.measureWidth(RESET_LABEL, textSize, letterSpacing);

        this.resetButtonWidth = padding * 2.0F + iconSize + gap * 2.0F + dividerWidth + textWidth;
        this.resetButtonHeight = height;
        this.resetButtonX = (mc.getWindow().getGuiScaledWidth() - this.resetButtonWidth) / 2.0F;
        this.resetButtonY = resolveResetButtonY(mc, visible);

        float mouseX = mouseX(mc);
        float mouseY = mouseY(mc);
        boolean hovered = inside(mouseX, mouseY,
                this.resetButtonX,
                this.resetButtonY,
                this.resetButtonWidth,
                this.resetButtonHeight);
        int foreground = hovered ? Theme.Colors.PRIMARY_BRIGHT : Theme.Colors.TEXT_TEXT;
        int iconColor = Theme.getAccent();
        float centerY = this.resetButtonY + height / 2.0F;

        Render2DUtil.rect(this.resetButtonX, this.resetButtonY, this.resetButtonWidth, height)
                .color(Theme.Colors.BACKGROUND_PRIMARY_50)
                .radius(radius)
                .border(Math.max(0.5F, 0.5F * unit), Theme.Colors.OUTLINES_MEDIUM)
                .blur(8.0F * unit)
                .draw();

        float cursor = this.resetButtonX + padding;
        Render2DUtil.texture(cursor, centerY - iconSize / 2.0F, iconSize, iconSize, RESET_ICON)
                .color(iconColor)
                .draw();
        cursor += iconSize + gap;

        Render2DUtil.rect(cursor, centerY - iconSize / 2.0F, dividerWidth, iconSize)
                .color(RESET_DIVIDER_COLOR)
                .draw();
        cursor += dividerWidth + gap;

        Render2DUtil.text(cursor, font.centeredTextY(centerY, textSize), textSize, RESET_LABEL)
                .style(UiFontStyle.MEDIUM)
                .color(foreground)
                .draw();
    }

    private float resolveResetButtonY(Minecraft mc, List<HudElement> visible) {
        float preferredY = 18.0F;
        float minY = 4.0F;
        float maxY = Math.max(minY,
                mc.getWindow().getGuiScaledHeight() - this.resetButtonHeight - minY);
        for (float offset = 0.0F; offset <= maxY + preferredY; offset += 2.0F) {
            float above = preferredY - offset;
            if (above >= minY && resetButtonFits(visible, above)) {
                return above;
            }
            float below = preferredY + offset;
            if (offset > 0.0F && below <= maxY && resetButtonFits(visible, below)) {
                return below;
            }
        }
        return Math.min(preferredY, maxY);
    }

    private boolean resetButtonFits(List<HudElement> visible, float y) {
        float gap = 4.0F;
        for (HudElement element : visible) {
            if (element.overlaps(
                    this.resetButtonX - gap,
                    y - gap,
                    this.resetButtonWidth + gap * 2.0F,
                    this.resetButtonHeight + gap * 2.0F)) {
                return false;
            }
        }
        return true;
    }

    private List<HudElement> visibleElements() {
        List<HudElement> visible = new ArrayList<>(4);
        if (this.elements.isSelected(WATERMARK)) {
            visible.add(this.watermarkElement);
        }
        if (this.elements.isSelected(KEYBINDS)) {
            visible.add(this.keybindsElement);
        }
        if (this.elements.isSelected(POTIONS)) {
            visible.add(this.potionsElement);
        }
        if (this.elements.isSelected(TARGET)) {
            visible.add(this.targetElement);
        }
        if (this.elements.isSelected(NOTIFICATIONS)) {
            visible.add(this.notificationsElement);
        }
        if (this.elements.isSelected(INFORMATION)) {
            visible.add(this.bottomStatsElement);
            visible.add(this.tpsElement);
        }
        if (this.elements.isSelected(QUICK_USE)) {
            visible.add(this.quickUseElement);
        }
        if (this.elements.isSelected(COOLDOWNS)) {
            visible.add(this.cooldownsElement);
        }
        if (this.elements.isSelected(STAFF_LIST)) {
            visible.add(this.staffListElement);
        }
        if (this.elements.isSelected(PVE_STATE)) {
            visible.add(this.pveStatusElement);
        }
        return visible;
    }

    private void stopDrag(Minecraft mc) {
        this.draggedElement.stopDrag(mc);
        this.draggedElement = null;
        clearSnapGuides();
    }

    private static boolean controlDown(Minecraft mc) {
        long window = mc.getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private static float mouseX(Minecraft mc) {
        return (float) mc.mouseHandler.getScaledXPos(mc.getWindow());
    }

    private static float mouseY(Minecraft mc) {
        return (float) mc.mouseHandler.getScaledYPos(mc.getWindow());
    }

    private static boolean inside(float mouseX, float mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private void migrateLegacySize() {
        if (this.sizeMigrationChecked) {
            return;
        }
        this.sizeMigrationChecked = true;
        if (MenuConfigStore.getBoolean(SIZE_MIGRATION_KEY, false)) {
            return;
        }
        if (this.size.getValue() == LEGACY_DEFAULT_SIZE) {
            this.size.setValue(100.0);
        }
        MenuConfigStore.save(data -> data.addProperty(SIZE_MIGRATION_KEY, true));
    }

    private void rebaseSizeTo153Baseline() {
        if (this.sizeRebaseChecked) {
            return;
        }
        this.sizeRebaseChecked = true;
        if (MenuConfigStore.getBoolean(SIZE_REBASE_KEY, false)) {
            return;
        }
        if (this.size.getValue() == PRE_REBASE_SIZE) {
            this.size.setValue(100.0);
        }
        MenuConfigStore.save(data -> data.addProperty(SIZE_REBASE_KEY, true));
    }
}
