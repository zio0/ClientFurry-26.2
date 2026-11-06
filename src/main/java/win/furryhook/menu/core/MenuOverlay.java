package win.furryhook.menu.core;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import win.furryhook.event.events.screen.ScreenMouseButtonEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ButtonSetting;
import win.furryhook.feature.setting.ColorSetting;
import win.furryhook.feature.setting.InputBindSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.feature.setting.Setting;
import win.furryhook.feature.setting.TextSetting;
import win.furryhook.menu.i18n.UiLanguage;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.DiscordPresence;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.CategoryIconGlyphs;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.TextAlign;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Container shell, category sidebar, module grid and a per-feature settings popup.
 *
 * Everything animates through {@link #anim(Object, float, float)}: a per-element
 * exponential smoother keyed by object identity, stepped by the real frame delta so
 * motion stays consistent regardless of FPS. render() runs every GUI frame while the
 * menu is open, so it doubles as the animation + drag polling loop — there is no
 * dedicated mouse-move/tick hook wired in.
 *
 * Layout sizes are exact Figma pixels specced against 2560x1440; the panel renders at
 * that same fraction of the actual screen so it lands on the Figma size at 2560x1440
 * and scales proportionally everywhere else.
 */
public final class MenuOverlay {
    private static final float PANEL_WIDTH = 1052.0F;
    private static final float PANEL_HEIGHT = 663.0F;
    private static final float REFERENCE_SCREEN_WIDTH = 2560.0F;
    private static final float REFERENCE_SCREEN_HEIGHT = 1440.0F;
    private static final float PANEL_RADIUS = 10.0F;
    private static final float PANEL_ALPHA = 0.95F;
    private static final float PANEL_BLUR_ALPHA = 0.95F;
    private static final float BLUR_RADIUS = 24.0F;
    // Darker tint than the shared HUD panel so the menu backdrop reads deeper without touching the HUD.
    private static final int MENU_PANEL = ColorUtil.rgb(10, 11, 13);

    private static final float SIDEBAR_WIDTH = 195.0F;
    private static final float SIDEBAR_ITEM_HEIGHT = 38.0F;
    private static final float SIDEBAR_ITEM_GAP = 6.0F;
    private static final float SIDEBAR_ICON_PADDING_X = 14.0F;
    private static final float SIDEBAR_ICON_SIZE = 15.0F;
    private static final float SIDEBAR_ICON_TEXT_GAP = 12.0F;
    private static final float SIDEBAR_LABEL_SIZE = 21.0F;
    private static final float DIVIDER_THICKNESS = 1.0F;

    private static final float SIDEBAR_PILL_WIDTH = 183.0F;
    private static final float SIDEBAR_PILL_HEIGHT = 38.0F;
    private static final float SIDEBAR_PILL_RADIUS = 12.0F;

    private static final float SEARCH_MARGIN_X = 14.0F;
    private static final float SEARCH_MARGIN_BOTTOM = 14.0F;
    private static final float SEARCH_HEIGHT = 36.0F;
    private static final float SEARCH_RADIUS = 10.0F;
    private static final float SEARCH_PADDING_X = 12.0F;
    private static final float SEARCH_ICON_SIZE = 14.0F;
    private static final float SEARCH_LABEL_SIZE = 14.0F;
    private static final float SEARCH_BORDER_THICKNESS = 1.0F;
    private static final float SEARCH_BORDER_ALPHA = 0.5F;

    // Search suggestion dropdown (drops downward from the search bar).
    private static final float SEARCH_SUGGEST_GAP = 6.0F;
    private static final float SEARCH_SUGGEST_ROW_HEIGHT = 30.0F;
    private static final float SEARCH_SUGGEST_RADIUS = 10.0F;
    private static final int SEARCH_SUGGEST_MAX = 6;
    private static final long HIGHLIGHT_DURATION_NANOS = 3_000_000_000L;
    private static final float HIGHLIGHT_FADE_SECONDS = 0.6F;

    private static final float CONTENT_PADDING = 20.0F;
    private static final int MODULE_COLUMNS = 3;
    private static final float MODULE_CARD_HEIGHT = 54.0F;
    private static final float MODULE_CARD_GAP_X = 14.0F;
    private static final float MODULE_CARD_GAP_Y = 14.0F;
    private static final float MODULE_CARD_RADIUS = 11.0F;
    private static final float MODULE_CARD_PADDING_X = 16.0F;
    private static final float MODULE_LABEL_SIZE = 16.0F;
    private static final float MODULE_CARD_BORDER_THICKNESS = 1.0F;
    private static final float MODULE_CARD_BORDER_ALPHA = 0.55F;
    private static final float SCROLL_STEP = 46.0F;
    private static final float SCROLLBAR_WIDTH = 4.0F;

    private static final float TOGGLE_WIDTH = 40.0F;
    private static final float TOGGLE_HEIGHT = 22.0F;
    private static final float TOGGLE_KNOB_PADDING = 2.0F;
    private static final int TOGGLE_OFF_COLOR = 0xFF44454C;

    // Horizontal "…" affordance shown before the toggle on modules that have settings; opens the popup.
    private static final float MODULE_DOTS_GAP = 12.0F;    // spacing between the dots and the toggle
    private static final float MODULE_DOT_SIZE = 3.0F;     // diameter of each dot
    private static final float MODULE_DOT_SPACING = 5.0F;  // horizontal centre-to-centre
    private static final float MODULE_DOTS_HIT = 22.0F;    // square click region centred on the dots

    private static final float SETTINGS_GAP = 18.0F;
    private static final float SETTINGS_WIDTH = 316.0F;
    private static final float SETTINGS_PADDING = 16.0F;
    private static final float SETTINGS_RADIUS = 12.0F;
    private static final float SETTINGS_TITLE_SIZE = 18.0F;
    private static final float SETTINGS_SUBTITLE_SIZE = 12.0F;
    private static final float SETTINGS_TITLE_GAP = 4.0F;
    private static final float SETTINGS_DIVIDER_GAP_TOP = 11.0F;
    private static final float SETTINGS_DIVIDER_GAP_BOTTOM = 10.0F;
    private static final float SETTINGS_ROW_HEIGHT = 27.0F;
    private static final float SETTINGS_SLIDER_ROW_HEIGHT = 33.0F;
    private static final float SETTINGS_ROW_GAP = 7.0F;
    private static final float SETTINGS_LABEL_SIZE = 14.0F;
    private static final float SETTINGS_VALUE_SIZE = 12.0F;
    private static final float SETTINGS_PILL_HEIGHT = 23.0F;
    private static final float SETTINGS_PILL_MIN_WIDTH = 82.0F;
    private static final float SETTINGS_PILL_PADDING_X = 10.0F;
    private static final float SETTINGS_PILL_RADIUS = 7.0F;
    private static final float SETTINGS_PILL_GAP = 10.0F;
    private static final float SETTINGS_WRAP_EXTRA = 25.0F;
    private static final float SETTINGS_CHIP_HEIGHT = 24.0F;
    private static final float SETTINGS_CHIP_PADDING_X = 12.0F;
    private static final float SETTINGS_CHIP_GAP_X = 6.0F;
    private static final float SETTINGS_CHIP_GAP_Y = 8.0F;
    private static final float SETTINGS_CHIP_LABEL_HEIGHT = 18.0F;
    private static final float SETTINGS_TRACK_HEIGHT = 4.0F;
    private static final float SETTINGS_KNOB_DIAMETER = 11.0F;
    private static final float SETTINGS_SWATCH_WIDTH = 34.0F;
    private static final float SETTINGS_SWATCH_HEIGHT = 20.0F;
    private static final float SETTINGS_OPTION_ROW_HEIGHT = 24.0F;
    private static final float SETTINGS_BUTTON_PADDING_X = 12.0F;
    private static final float SETTINGS_SLIDE_IN = 26.0F;

    private static final float ACCOUNT_MARGIN = 12.0F;
    private static final float ACCOUNT_HEIGHT = 46.0F;
    private static final float ACCOUNT_AVATAR = 34.0F;
    private static final float ACCOUNT_GEAR = 17.0F;
    private static final float ACCOUNT_GAP = 10.0F;
    private static final float ACCOUNT_LABEL_SIZE = 10.0F;
    private static final float ACCOUNT_HANDLE_SIZE = 13.0F;
    private static final String ACCOUNT_NAME = "Your Account";
    private static final String ACCOUNT_HANDLE = "t.me/i284e";

    private static final float CLIENT_WIDTH = 300.0F;
    private static final float CLIENT_SUBTITLE_WIDTH = 260.0F;

    private static final float COLOR_PICKER_PADDING = 8.0F;
    private static final float COLOR_PICKER_SV_SIZE = 150.0F;
    private static final float COLOR_PICKER_HUE_HEIGHT = 12.0F;
    private static final float COLOR_PICKER_GAP = 10.0F;
    private static final float COLOR_PICKER_HANDLE = 5.0F;
    private static final int[] HUE_STOPS = {
            ColorUtil.rgb(255, 0, 0), ColorUtil.rgb(255, 255, 0), ColorUtil.rgb(0, 255, 0),
            ColorUtil.rgb(0, 255, 255), ColorUtil.rgb(0, 0, 255), ColorUtil.rgb(255, 0, 255),
            ColorUtil.rgb(255, 0, 0)
    };

    private static final int CATEGORY_BASE = 0xFF3C3C43;
    private static final float CATEGORY_UNSELECTED_ALPHA = 0.6F;
    private static final float CATEGORY_SELECTED_BG_ALPHA = 0.30F;
    private static final float HOVER_FILL_ALPHA = 0.16F;
    private static final float BACKGROUND_DIM_ALPHA = 0.68F;

    // Editorial monochrome ramp: nothing is pure white — titles sit brightest, row
    // labels a step down, values/meta muted. Structure comes from a hairline, not fills.
    private static final int TEXT_PRIMARY = Theme.Colors.TEXT_TITLE;
    private static final int TEXT_ROW = Theme.Colors.TEXT_TEXT;
    private static final int TEXT_MUTED = Theme.Colors.SECONDARY;
    private static final int HAIRLINE = ColorUtil.withAlpha(ColorUtil.WHITE, 0.09F);
    private static final int SEPARATOR = ColorUtil.withAlpha(ColorUtil.WHITE, 0.055F);

    // Animation speeds (higher = snappier). Frame-delta driven, so these are 1/seconds-ish.
    // Tuned a touch slower than snappy for a softer, gliding feel.
    private static final float SPEED_TOGGLE = 15.0F;
    private static final float SPEED_HOVER = 17.0F;
    private static final float SPEED_PILL = 16.0F;
    private static final float SPEED_COLOR = 14.0F;
    private static final float SPEED_APPEAR = 12.0F;
    private static final float SPEED_FLYOUT = 17.0F;
    private static final float SPEED_SCROLL = 16.0F;
    private static final float SPEED_OPEN = 11.0F;
    private static final float SPEED_CLOSE = 24.0F;

    // Sidebar is grouped under section headers; each group lists the categories under it.
    private static final String[] SECTION_LABELS = {"MAIN", "COMMON", "OTHER"};
    private static final FeatureCategory[][] SECTION_CATEGORIES = {
            {FeatureCategory.COMBAT, FeatureCategory.MOVEMENT},
            {FeatureCategory.VISUAL, FeatureCategory.PLAYER, FeatureCategory.MISC},
            {FeatureCategory.COSMETICS, FeatureCategory.SCRIPTS}
    };

    private static final float SIDEBAR_HEADER_HEIGHT = 14.0F;
    private static final float SIDEBAR_HEADER_LABEL_SIZE = 11.0F;
    private static final float SIDEBAR_HEADER_GAP = 6.0F;   // header baseline -> first item
    private static final float SIDEBAR_GROUP_GAP = 12.0F;   // extra space before a group (except the first)

    private static Identifier categoryIcon(FeatureCategory category) {
        return switch (category) {
            case VISUAL -> Textures.Icons.EYE;
            case PLAYER -> Textures.Icons.USER_ROUND;
            case MISC -> Textures.Icons.BOXES;
            case COSMETICS -> Textures.Icons.GIFT;
            case SCRIPTS -> Textures.Icons.COMMAND;
            // COMBAT / MOVEMENT render as font glyphs, never reach here.
            default -> Textures.Icons.BOXES;
        };
    }

    /** One laid-out sidebar row: either a section header ({@code header != null}) or a category. */
    private record SidebarRow(FeatureCategory category, String header, float y, float height) {
    }

    private static List<SidebarRow> sidebarRows(float panelY, float scale, float topPadding) {
        List<SidebarRow> rows = new ArrayList<>();
        float y = panelY + topPadding;
        for (int g = 0; g < SECTION_LABELS.length; g++) {
            if (g > 0) {
                y += SIDEBAR_GROUP_GAP * scale;
            }
            rows.add(new SidebarRow(null, SECTION_LABELS[g], y, SIDEBAR_HEADER_HEIGHT * scale));
            y += SIDEBAR_HEADER_HEIGHT * scale + SIDEBAR_HEADER_GAP * scale;
            FeatureCategory[] cats = SECTION_CATEGORIES[g];
            for (int c = 0; c < cats.length; c++) {
                if (c > 0) {
                    y += SIDEBAR_ITEM_GAP * scale;
                }
                rows.add(new SidebarRow(cats[c], null, y, SIDEBAR_ITEM_HEIGHT * scale));
                y += SIDEBAR_ITEM_HEIGHT * scale;
            }
        }
        return rows;
    }

    private enum SettingRowKind {
        CHECKBOX, MODE_TOGGLE, MODE_OPTION, SLIDER, COLOR_TOGGLE, COLOR_SV, COLOR_HUE,
        MULTI_OPTION, TEXT_FIELD, BUTTON, BIND_FIELD, BIND_HEADER
    }

    private record SettingRow(SettingRowKind kind, Setting<?> setting, String optionValue, int optionIndex,
                              float x, float y, float width, float height) {
        SettingRow(SettingRowKind kind, Setting<?> setting, float x, float y, float width, float height) {
            this(kind, setting, null, -1, x, y, width, height);
        }
    }

    private static boolean open;
    private static FeatureCategory selectedCategory = FeatureCategory.COMBAT;
    private static float lastPanelX;
    private static float lastPanelY;
    private static float lastScale = 1.0F;
    private static float moduleScrollOffset;
    private static float moduleScrollShown;
    private static float settingsScrollShown;

    private static Feature settingsFeature;
    private static Feature renderedSettingsFeature;
    private static Setting<?> openDropdown;
    private static Feature capturingBindFeature;
    private static InputBindSetting capturingBindInput;
    private static TextSetting focusedTextSetting;
    private static NumberSetting draggingSlider;
    private static float draggingSliderTrackX;
    private static float draggingSliderTrackWidth;
    private static ColorSetting draggingColor;
    private static int colorDragMode; // 1 = saturation/value square, 2 = hue bar
    private static float lastSvX, lastSvY, lastSvSize;
    private static float lastHueX, lastHueY, lastHueWidth, lastHueHeight;

    private static float settingsScrollOffset;
    private static Feature settingsScrollFeature;
    private static float lastSettingsScrollMax;

    // Client-settings panel (opened by the account-card gear, sits left of the main panel).
    private static boolean clientPanelOpen;
    private static final List<SettingRow> lastClientRows = new ArrayList<>();
    private static List<SettingRow> rowSink;
    private static float lastGearX, lastGearY, lastGearSize;
    private static float lastClientPanelX, lastClientPanelY, lastClientPanelWidth, lastClientPanelHeight;

    private static final ModeSetting languageSetting =
            new ModeSetting("Language", UiLanguage.current() == UiLanguage.RUSSIAN ? "Russian" : "English", "English", "Russian");
    private static final NumberSetting guiScaleSetting =
            new NumberSetting("Gui Scale", 1.0, 0.75, 1.35, 0.01, "x");
    private static final ColorSetting themeColorSetting =
            new ColorSetting("Custom Color", Theme.getAccent());
    // How the accent behaves: the picked swatch, or one of the animated cycles that every
    // accent-driven element (toggles, sliders, Sync-mode features) follows.
    private static final ModeSetting accentModeSetting = new ModeSetting("Color Mode",
            Theme.AccentMode.STATIC.label(),
            Theme.AccentMode.STATIC.label(),
            Theme.AccentMode.RAINBOW.label(),
            Theme.AccentMode.ASTOLFO.label());

    // Ambient layers drawn behind the panels by MenuBackgroundFx.
    private static final BooleanSetting backgroundLightSetting = new BooleanSetting("Background Light", true);
    private static final BooleanSetting backgroundEmbersSetting = new BooleanSetting("Background Embers", true);
    // Accent comet running clockwise around the main panel's border.
    private static final BooleanSetting glowSetting = new BooleanSetting("Glow", false);

    static {
        languageSetting.setChangeListener(() ->
                UiLanguage.set(languageSetting.is("Russian") ? UiLanguage.RUSSIAN : UiLanguage.ENGLISH));
        themeColorSetting.setChangeListener(() -> {
            int color = themeColorSetting.getValue();
            Theme.saveAccentPreset(Theme.CUSTOM_ACCENT_INDEX, color);
            MenuConfigStore.save(data -> data.addProperty("theme.accentColor", color));
        });
        accentModeSetting.setChangeListener(() -> {
            Theme.AccentMode mode = Theme.AccentMode.byLabel(accentModeSetting.getValue());
            Theme.setAccentMode(mode);
            MenuConfigStore.save(data -> data.addProperty("theme.accentMode", mode.label()));
        });
        backgroundLightSetting.setChangeListener(() ->
                MenuConfigStore.save(data -> data.addProperty("background.light", backgroundLightSetting.getValue())));
        backgroundEmbersSetting.setChangeListener(() -> {
            MenuConfigStore.save(data -> data.addProperty("background.embers", backgroundEmbersSetting.getValue()));
            if (!backgroundEmbersSetting.getValue()) {
                MenuBackgroundFx.releaseEmbers();
            }
        });
        glowSetting.setChangeListener(() ->
                MenuConfigStore.save(data -> data.addProperty("menu.glow", glowSetting.getValue())));
    }

    /** Restores the custom accent color and mode saved to {@code menu.json} on a previous run. */
    public static void loadSavedTheme() {
        int color = MenuConfigStore.getInt("theme.accentColor", 0);
        if (color != 0) {
            themeColorSetting.setValue(color);
        }
        accentModeSetting.setValue(Theme.AccentMode
                .byLabel(MenuConfigStore.getString("theme.accentMode", Theme.AccentMode.STATIC.label()))
                .label());
        backgroundLightSetting.setValue(MenuConfigStore.getBoolean("background.light", true));
        backgroundEmbersSetting.setValue(MenuConfigStore.getBoolean("background.embers", true));
        glowSetting.setValue(MenuConfigStore.getBoolean("menu.glow", false));
    }

    private static final StringBuilder searchQuery = new StringBuilder();
    private static boolean searchFocused;
    private static float lastSearchX;
    private static float lastSearchY;
    private static float lastSearchWidth;
    private static float lastSearchHeight;

    // Search suggestion dropdown geometry + the module a pick jumped to (dims the rest while it fades).
    private static final List<Feature> lastSuggestionFeatures = new ArrayList<>();
    private static float lastSuggestionX;
    private static float lastSuggestionY;
    private static float lastSuggestionWidth;
    private static float lastSuggestionRowHeight;
    private static Feature highlightedFeature;
    private static long highlightUntilNanos;

    private static float lastSettingsPanelX;
    private static float lastSettingsPanelY;
    private static float lastSettingsPanelWidth;
    private static float lastSettingsPanelHeight;
    private static final List<SettingRow> lastSettingsRows = new ArrayList<>();

    private static boolean draggingMain;
    private static boolean draggingSettings;
    private static float dragLastMouseX;
    private static float dragLastMouseY;
    private static float mainDragOffsetX;
    private static float mainDragOffsetY;
    private static float settingsDragOffsetX;
    private static float settingsDragOffsetY;

    private static final Map<Object, Float> ANIM = new IdentityHashMap<>();
    private static long lastFrameNanos;
    private static float frameDelta = 1.0F / 60.0F;
    private static int hoverMouseX;
    private static int hoverMouseY;

    private MenuOverlay() {
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    public static boolean toggle(Minecraft minecraft) {
        if (open) {
            close(minecraft);
            return true;
        }
        open(minecraft);
        return true;
    }

    public static void close(Minecraft minecraft) {
        if (!open) {
            return;
        }
        open = false;
        settingsFeature = null;
        openDropdown = null;
        capturingBindFeature = null;
        capturingBindInput = null;
        focusedTextSetting = null;
        draggingSlider = null;
        draggingMain = false;
        draggingSettings = false;
        searchFocused = false;
        highlightedFeature = null;
        if (shouldReturnMouseToGame(minecraft)) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    public static boolean suspendForReload(Minecraft minecraft) {
        if (!open) {
            return false;
        }
        close(minecraft);
        return true;
    }

    public static void resumeAfterReload(Minecraft minecraft) {
        // Nothing to resume: there is no per-open state left to restore yet.
    }

    public static boolean isOpen() {
        return open;
    }

    public static boolean isVisible() {
        return open;
    }

    public static boolean blocksInput() {
        return open;
    }

    // ── input ────────────────────────────────────────────────────────────────

    public static boolean handleKey(int key) {
        if (!open) {
            return false;
        }
        if (capturingBindFeature != null) {
            if (isBindClearKey(key)) {
                capturingBindFeature.getBind().clear();
            } else {
                capturingBindFeature.getBind().setSingle(key);
            }
            capturingBindFeature = null;
            return true;
        }
        if (capturingBindInput != null) {
            if (isBindClearKey(key)) {
                capturingBindInput.clear();
            } else {
                capturingBindInput.setKey(key);
            }
            capturingBindInput = null;
            return true;
        }
        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (searchQuery.length() > 0) {
                    searchQuery.deleteCharAt(searchQuery.length() - 1);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER) {
                List<Feature> matches = searchMatches();
                if (!matches.isEmpty()) {
                    selectFromSearch(matches.get(0));
                } else {
                    searchFocused = false;
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            return true;
        }
        if (focusedTextSetting != null) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                String value = focusedTextSetting.getValue();
                if (!value.isEmpty()) {
                    focusedTextSetting.setValue(value.substring(0, value.length() - 1));
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                focusedTextSetting = null;
                return true;
            }
            return true;
        }
        return false;
    }

    // Escape / Delete / Backspace while capturing wipe the bind instead of assigning one.
    private static boolean isBindClearKey(int key) {
        return key == GLFW.GLFW_KEY_ESCAPE
                || key == GLFW.GLFW_KEY_DELETE
                || key == GLFW.GLFW_KEY_BACKSPACE;
    }

    public static boolean handleCharacter(int codePoint) {
        if (searchFocused) {
            searchQuery.append(new String(Character.toChars(codePoint)));
            return true;
        }
        if (focusedTextSetting == null) {
            return false;
        }
        if (focusedTextSetting.getValue().length() >= focusedTextSetting.getMaxLength()) {
            return true;
        }
        focusedTextSetting.setValue(focusedTextSetting.getValue() + new String(Character.toChars(codePoint)));
        return true;
    }

    public static void handleScroll(double vertical) {
        if (!open) {
            return;
        }
        float step = (float) vertical * SCROLL_STEP * lastScale;
        // Scroll whichever surface the cursor is over: the settings popup takes priority.
        if (settingsFeature != null && insideRect(hoverMouseX, hoverMouseY,
                lastSettingsPanelX, lastSettingsPanelY, lastSettingsPanelWidth, lastSettingsPanelHeight)) {
            settingsScrollOffset = MathUtil.clamp(settingsScrollOffset - step, 0.0F, lastSettingsScrollMax);
            return;
        }
        float max = moduleScrollMax(lastScale, PANEL_HEIGHT * lastScale);
        moduleScrollOffset = MathUtil.clamp(moduleScrollOffset - step, 0.0F, max);
    }

    public static boolean handleMouseButton(Minecraft minecraft, int button, int action) {
        if (!open) {
            return false;
        }
        if (action == GLFW.GLFW_RELEASE) {
            draggingMain = false;
            draggingSettings = false;
            draggingSlider = null;
            draggingColor = null;
            colorDragMode = 0;
            return true;
        }
        if (action != GLFW.GLFW_PRESS) {
            return true;
        }

        int mouseX = mouseX(minecraft);
        int mouseY = mouseY(minecraft);

        if (capturingBindFeature != null) {
            capturingBindFeature.getBind().setSingle(BindSetting.mouse(button));
            capturingBindFeature = null;
            return true;
        }
        if (capturingBindInput != null) {
            capturingBindInput.setMouse(button);
            capturingBindInput = null;
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            Feature feature = moduleCardAt(lastPanelX, lastPanelY, lastScale, mouseX, mouseY);
            if (feature != null) {
                capturingBindFeature = feature;
            }
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (insideRect(mouseX, mouseY, lastSearchX, lastSearchY, lastSearchWidth, lastSearchHeight)) {
                searchFocused = !searchFocused;
                focusedTextSetting = null;
                return true;
            }
            if (handleSuggestionClick(mouseX, mouseY)) {
                return true;
            }
            searchFocused = false;

            // Account-card gear toggles the client-settings panel.
            if (lastGearSize > 0.0F && insideRect(mouseX, mouseY, lastGearX, lastGearY, lastGearSize, lastGearSize)) {
                clientPanelOpen = !clientPanelOpen;
                openDropdown = null;
                return true;
            }

            if (handleClientClick(mouseX, mouseY)) {
                return true;
            }
            if (handleSettingsClick(mouseX, mouseY)) {
                return true;
            }
            FeatureCategory clicked = sidebarCategoryAt(lastPanelX, lastPanelY, lastScale, mouseX, mouseY);
            if (clicked != null) {
                selectedCategory = clicked;
                moduleScrollOffset = 0.0F;
                return true;
            }
            // "…" opens (or closes) the settings popup, same as a right-click on the card.
            Feature dotsFeature = moduleDotsAt(lastPanelX, lastPanelY, lastScale, mouseX, mouseY);
            if (dotsFeature != null) {
                settingsFeature = settingsFeature == dotsFeature ? null : dotsFeature;
                openDropdown = null;
                return true;
            }
            Feature feature = moduleCardAt(lastPanelX, lastPanelY, lastScale, mouseX, mouseY);
            if (feature != null) {
                if (feature.isToggleable()) {
                    feature.setEnabled(!feature.isEnabled());
                }
                return true;
            }
            // Missed everything interactive: dismiss any open popup / focus.
            openDropdown = null;
            focusedTextSetting = null;
            // Empty panel space acts as a drag handle.
            if (settingsFeature != null && insideRect(mouseX, mouseY, lastSettingsPanelX, lastSettingsPanelY, lastSettingsPanelWidth, lastSettingsPanelHeight)) {
                draggingSettings = true;
                dragLastMouseX = mouseX;
                dragLastMouseY = mouseY;
                return true;
            }
            if (clientPanelOpen && insideRect(mouseX, mouseY, lastClientPanelX, lastClientPanelY, lastClientPanelWidth, lastClientPanelHeight)) {
                return true;
            }
            if (insideRect(mouseX, mouseY, lastPanelX, lastPanelY, PANEL_WIDTH * lastScale, PANEL_HEIGHT * lastScale)) {
                draggingMain = true;
                dragLastMouseX = mouseX;
                dragLastMouseY = mouseY;
                return true;
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Feature feature = moduleCardAt(lastPanelX, lastPanelY, lastScale, mouseX, mouseY);
            if (feature != null && hasSettings(feature)) {
                settingsFeature = settingsFeature == feature ? null : feature;
                openDropdown = null;
            }
        }
        return true;
    }

    /**
     * Row hit-testing reuses the exact bounds {@link #renderSettingsPanel} laid out last
     * frame — one source of truth for both drawing and clicks. Checked back-to-front since
     * flyouts (mode/color/multi options) are appended last and visually float on top of
     * whatever row happens to sit underneath them.
     */
    private static boolean handleSettingsClick(int mouseX, int mouseY) {
        return settingsFeature != null && handleRowClick(lastSettingsRows, mouseX, mouseY);
    }

    private static boolean handleClientClick(int mouseX, int mouseY) {
        return clientPanelOpen && handleRowClick(lastClientRows, mouseX, mouseY);
    }

    private static boolean handleSuggestionClick(int mouseX, int mouseY) {
        if (lastSuggestionFeatures.isEmpty()) {
            return false;
        }
        float totalHeight = lastSuggestionRowHeight * lastSuggestionFeatures.size();
        if (!insideRect(mouseX, mouseY, lastSuggestionX, lastSuggestionY, lastSuggestionWidth, totalHeight)) {
            return false;
        }
        int index = (int) ((mouseY - lastSuggestionY) / lastSuggestionRowHeight);
        if (index < 0 || index >= lastSuggestionFeatures.size()) {
            return false;
        }
        selectFromSearch(lastSuggestionFeatures.get(index));
        return true;
    }

    private static boolean handleRowClick(List<SettingRow> rows, int mouseX, int mouseY) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            SettingRow row = rows.get(i);
            if (!insideRect(mouseX, mouseY, row.x(), row.y(), row.width(), row.height())) {
                continue;
            }
            applyRow(row, mouseX, mouseY);
            return true;
        }
        return false;
    }

    private static void applyRow(SettingRow row, int mouseX, int mouseY) {
        switch (row.kind()) {
            case CHECKBOX -> {
                BooleanSetting setting = (BooleanSetting) row.setting();
                setting.setValue(!setting.getValue());
            }
            case MODE_TOGGLE, COLOR_TOGGLE ->
                    openDropdown = openDropdown == row.setting() ? null : row.setting();
            case MODE_OPTION -> ((ModeSetting) row.setting()).setValue(row.optionValue());
            case COLOR_SV -> {
                draggingColor = (ColorSetting) row.setting();
                colorDragMode = 1;
                applyColorSv(draggingColor, mouseX, mouseY);
            }
            case COLOR_HUE -> {
                draggingColor = (ColorSetting) row.setting();
                colorDragMode = 2;
                applyColorHue(draggingColor, mouseX);
            }
            case MULTI_OPTION -> ((MultiSelectSetting) row.setting()).toggle(row.optionValue());
            case SLIDER -> {
                NumberSetting setting = (NumberSetting) row.setting();
                draggingSlider = setting;
                // Lock the track geometry at grab time: the Gui Scale slider resizes the whole
                // GUI, which would otherwise move the track under the cursor and make the
                // mouse->value mapping oscillate to the extremes mid-drag.
                draggingSliderTrackX = row.x();
                draggingSliderTrackWidth = row.width();
                applySliderValue(setting, mouseX, draggingSliderTrackX, draggingSliderTrackWidth);
            }
            case TEXT_FIELD -> focusedTextSetting = (TextSetting) row.setting();
            case BUTTON -> ((ButtonSetting) row.setting()).press();
            case BIND_FIELD -> capturingBindInput = (InputBindSetting) row.setting();
            case BIND_HEADER -> capturingBindFeature = settingsFeature;
        }
    }

    private static void applySliderValue(NumberSetting setting, float mouseX, float trackX, float trackWidth) {
        double progress = MathUtil.clamp01((mouseX - trackX) / trackWidth);
        setting.setValue(setting.getMin() + (setting.getMax() - setting.getMin()) * progress);
    }

    private static void applyColorSv(ColorSetting setting, float mouseX, float mouseY) {
        if (lastSvSize <= 0.0F) {
            return;
        }
        float sat = MathUtil.clamp01((mouseX - lastSvX) / lastSvSize);
        float val = 1.0F - MathUtil.clamp01((mouseY - lastSvY) / lastSvSize);
        float hue = ColorUtil.hsv(setting.getValue())[0];
        setting.setValue(ColorUtil.fromHsv(hue, sat, val, 255));
    }

    private static void applyColorHue(ColorSetting setting, float mouseX) {
        if (lastHueWidth <= 0.0F) {
            return;
        }
        float hue = MathUtil.clamp01((mouseX - lastHueX) / lastHueWidth);
        float[] hsv = ColorUtil.hsv(setting.getValue());
        float sat = Math.max(0.05F, hsv[1]);
        float val = Math.max(0.15F, hsv[2]);
        setting.setValue(ColorUtil.fromHsv(hue, sat, val, 255));
    }

    private static boolean insideRect(float pointX, float pointY, float x, float y, float width, float height) {
        return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
    }

    public static boolean handleScreenMouseButton(Minecraft minecraft, int button, ScreenMouseButtonEvent.Action action) {
        if (action == ScreenMouseButtonEvent.Action.DRAG) {
            return open;
        }
        return handleMouseButton(minecraft, button, action == ScreenMouseButtonEvent.Action.RELEASE ? GLFW.GLFW_RELEASE : GLFW.GLFW_PRESS);
    }

    // ── render ───────────────────────────────────────────────────────────────

    public static void render(Minecraft minecraft, GuiGraphicsExtractor guiGraphicsExtractor, int screenWidth, int screenHeight) {
        long now = System.nanoTime();
        frameDelta = lastFrameNanos == 0L ? 1.0F / 60.0F : MathUtil.clamp((now - lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
        lastFrameNanos = now;

        // Whole-GUI open/close zoom: `open` cuts input instantly; this value keeps rendering
        // the fade-out until it settles, so closing animates instead of vanishing.
        float openRaw = anim("openAnim", open ? 1.0F : 0.0F, open ? SPEED_OPEN : SPEED_CLOSE);
        if (!open && openRaw < 0.01F) {
            return;
        }
        float openT = easeOutCubic(openRaw);

        hoverMouseX = mouseX(minecraft);
        hoverMouseY = mouseY(minecraft);

        // Smoothed so the whole GUI eases to the new size instead of snapping each drag tick.
        float menuScale = anim("menuScale", guiScaleSetting.getValue().floatValue(), 13.0F);
        float width = screenWidth * (PANEL_WIDTH / REFERENCE_SCREEN_WIDTH) * menuScale;
        float height = screenHeight * (PANEL_HEIGHT / REFERENCE_SCREEN_HEIGHT) * menuScale;
        float scale = width / PANEL_WIDTH;

        if (draggingMain || draggingSettings) {
            float deltaX = hoverMouseX - dragLastMouseX;
            float deltaY = hoverMouseY - dragLastMouseY;
            if (draggingMain) {
                mainDragOffsetX += deltaX;
                mainDragOffsetY += deltaY;
            } else {
                settingsDragOffsetX += deltaX;
                settingsDragOffsetY += deltaY;
            }
            dragLastMouseX = hoverMouseX;
            dragLastMouseY = hoverMouseY;
        }
        if (draggingSlider != null) {
            updateSliderDrag(hoverMouseX);
        }
        if (draggingColor != null) {
            if (colorDragMode == 0) {
                draggingColor = null;
            } else if (colorDragMode == 1) {
                applyColorSv(draggingColor, hoverMouseX, hoverMouseY);
            } else {
                applyColorHue(draggingColor, hoverMouseX);
            }
        }

        float x = MathUtil.clamp((screenWidth - width) / 2.0F + mainDragOffsetX, 0.0F, Math.max(0.0F, screenWidth - width));
        float y = MathUtil.clamp((screenHeight - height) / 2.0F + mainDragOffsetY, 0.0F, Math.max(0.0F, screenHeight - height));
        lastPanelX = x;
        lastPanelY = y;
        lastScale = scale;

        moduleScrollOffset = MathUtil.clamp(moduleScrollOffset, 0.0F, moduleScrollMax(scale, height));
        moduleScrollShown = anim("moduleScroll", moduleScrollOffset, SPEED_SCROLL);

        // Dim the game behind the whole GUI, fading with the open animation.
        Render2DUtil.rect(0.0F, 0.0F, screenWidth, screenHeight)
                .color(ColorUtil.withAlpha(ColorUtil.BLACK, BACKGROUND_DIM_ALPHA * openT))
                .draw();

        // Ambient layer on top of the dim, underneath every panel: accent light pooling at
        // the bottom of the screen and embers drifting up out of it.
        MenuBackgroundFx.render(screenWidth, screenHeight, frameDelta, openT, scale,
                backgroundLightSetting.getValue(), backgroundEmbersSetting.getValue());

        float zoom = 0.95F + 0.05F * openT;
        float centerX = x + width / 2.0F;
        float centerY = y + height / 2.0F;
        var pose = guiGraphicsExtractor.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.scale(zoom, zoom);
        pose.translate(-centerX, -centerY);

        // Frosted backdrop: blurred game behind a translucent dark tint, matching the HUD.
        Render2DUtil.rect(x, y, width, height)
                .color(ColorUtil.withAlpha(MENU_PANEL, PANEL_BLUR_ALPHA))
                .radius(PANEL_RADIUS * scale)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), Theme.Colors.PANEL_BORDER)
                .blur(BLUR_RADIUS * scale, 1.0F)
                .draw();

        // Accent comet lapping the border, on top of the hairline but under the content.
        if (glowSetting.getValue()) {
            MenuBorderGlow.render(x, y, width, height, PANEL_RADIUS * scale, scale, openT, frameDelta);
        }

        renderSidebar(x, y, scale, height);
        renderSearchBar(x, y, scale, height);
        renderModules(x, y, scale, height);
        renderSearchSuggestions(x, y, scale, height);
        renderClientPanel(guiGraphicsExtractor, x, y, height, scale);
        renderSettingsPanel(guiGraphicsExtractor, x, y, width, height, scale);

        pose.popMatrix();
    }

    private static float easeOutCubic(float x) {
        float inv = 1.0F - x;
        return 1.0F - inv * inv * inv;
    }

    private static void updateSliderDrag(float mouseX) {
        if (draggingSliderTrackWidth > 0.0F) {
            applySliderValue(draggingSlider, mouseX, draggingSliderTrackX, draggingSliderTrackWidth);
        }
    }

    // ── sidebar ──────────────────────────────────────────────────────────────

    private static void renderSidebar(float panelX, float panelY, float scale, float panelHeight) {
        float sidebarWidth = SIDEBAR_WIDTH * scale;
        float dividerThickness = Math.max(0.5F, DIVIDER_THICKNESS * scale);
        int dividerColor = ColorUtil.withAlpha(CATEGORY_BASE, CATEGORY_UNSELECTED_ALPHA);
        Render2DUtil.rect(panelX + sidebarWidth - dividerThickness, panelY, dividerThickness, panelHeight)
                .color(dividerColor)
                .draw();

        renderAccountCard(panelX, panelY, sidebarWidth, scale);

        float itemHeight = SIDEBAR_ITEM_HEIGHT * scale;
        float pillWidth = SIDEBAR_PILL_WIDTH * scale;
        float pillHeight = SIDEBAR_PILL_HEIGHT * scale;
        float iconSize = SIDEBAR_ICON_SIZE * scale;
        float labelSize = SIDEBAR_LABEL_SIZE * scale;
        float topPadding = sidebarTopPadding(panelHeight, scale);
        float pillX = panelX + (sidebarWidth - pillWidth) / 2.0F;

        List<SidebarRow> rows = sidebarRows(panelY, scale, topPadding);

        // The selection pill slides to the selected category's row on an animated Y offset.
        float selectedItemY = topPadding;
        for (SidebarRow row : rows) {
            if (row.category() == selectedCategory) {
                selectedItemY = row.y() - panelY;
                break;
            }
        }
        float pillItemY = panelY + anim("sidebarPillY", selectedItemY, SPEED_PILL);
        Render2DUtil.rect(pillX, pillItemY + (itemHeight - pillHeight) / 2.0F, pillWidth, pillHeight)
                .color(ColorUtil.withAlpha(CATEGORY_BASE, CATEGORY_SELECTED_BG_ALPHA))
                .radius(SIDEBAR_PILL_RADIUS * scale)
                .draw();

        int accent = Theme.getAccent();
        int dim = ColorUtil.withAlpha(CATEGORY_BASE, CATEGORY_UNSELECTED_ALPHA);
        for (SidebarRow row : rows) {
            if (row.category() == null) {
                // Section header ("MAIN" / "COMMON" / "OTHER").
                float headerSize = SIDEBAR_HEADER_LABEL_SIZE * scale;
                float headerX = pillX + SIDEBAR_ICON_PADDING_X * scale;
                float headerY = UiFonts.sfProDisplay().centeredTextY(row.y() + row.height() / 2.0F, headerSize);
                Render2DUtil.text(headerX, headerY, headerSize, row.header())
                        .style(UiFontStyle.SEMIBOLD)
                        .color(dim)
                        .draw();
                continue;
            }

            FeatureCategory category = row.category();
            boolean active = category == selectedCategory;
            float itemY = row.y();
            boolean hovered = !active && insideRect(hoverMouseX, hoverMouseY, pillX, itemY + (itemHeight - pillHeight) / 2.0F, pillWidth, pillHeight);

            float activeT = anim(category, active ? 1.0F : 0.0F, SPEED_COLOR);
            int textColor = mixColor(dim, ColorUtil.WHITE, hovered ? Math.max(activeT, 0.4F) : activeT);
            int iconColor = mixColor(dim, accent, activeT);

            float iconX = pillX + SIDEBAR_ICON_PADDING_X * scale;
            float iconY = itemY + (itemHeight - iconSize) / 2.0F;
            if (CategoryIconGlyphs.isGlyph(category)) {
                // Combat/Movement use the same glyph icons as the Hotkeys HUD.
                var glyphFont = UiFonts.divineNotificationIcons();
                Render2DUtil.text(iconX, glyphFont.centeredTextY(itemY + itemHeight / 2.0F, iconSize),
                                iconSize, CategoryIconGlyphs.glyph(category))
                        .font(glyphFont)
                        .color(iconColor)
                        .draw();
            } else {
                Render2DUtil.texture(iconX, iconY, iconSize, iconSize, categoryIcon(category))
                        .color(iconColor)
                        .draw();
            }

            float textX = iconX + iconSize + SIDEBAR_ICON_TEXT_GAP * scale;
            float textY = UiFonts.sfProDisplay().centeredTextY(itemY + itemHeight / 2.0F, labelSize);
            Render2DUtil.text(textX, textY, labelSize, category.getDisplayName())
                    .style(UiFontStyle.MEDIUM)
                    .color(textColor)
                    .draw();
        }
    }

    private static void renderAccountCard(float panelX, float panelY, float sidebarWidth, float scale) {
        float margin = ACCOUNT_MARGIN * scale;
        float cardX = panelX + margin;
        float cardY = panelY + margin;
        float cardWidth = sidebarWidth - margin * 2.0F;
        float cardHeight = ACCOUNT_HEIGHT * scale;

        float avatarSize = ACCOUNT_AVATAR * scale;
        float avatarX = cardX;
        float avatarY = cardY + (cardHeight - avatarSize) / 2.0F;
        Render2DUtil.texture(avatarX, avatarY, avatarSize, avatarSize, Textures.Header.FURRY_AVATAR)
                .color(ColorUtil.WHITE)
                .radius(avatarSize / 2.0F)
                .draw();

        float gearSize = ACCOUNT_GEAR * scale;
        float gearX = cardX + cardWidth - gearSize;
        float gearY = cardY + (cardHeight - gearSize) / 2.0F;
        lastGearX = gearX;
        lastGearY = gearY;
        lastGearSize = gearSize;
        boolean gearHover = insideRect(hoverMouseX, hoverMouseY, gearX, gearY, gearSize, gearSize);
        float gearT = anim("gear", clientPanelOpen || gearHover ? 1.0F : 0.0F, SPEED_COLOR);
        Render2DUtil.texture(gearX, gearY, gearSize, gearSize, Textures.Header.SETTINGS)
                .color(mixColor(TEXT_MUTED, Theme.getAccent(), gearT))
                .draw();

        float textX = avatarX + avatarSize + ACCOUNT_GAP * scale;
        float labelSize = ACCOUNT_LABEL_SIZE * scale;
        float handleSize = ACCOUNT_HANDLE_SIZE * scale;
        float personSize = labelSize;

        // Top line: person glyph + "Your Account", both centred on the same baseline.
        float labelCenterY = cardY + cardHeight * 0.34F;
        float iconY = labelCenterY - personSize / 2.0F;
        Render2DUtil.texture(textX, iconY, personSize, personSize, Textures.Icons.USER_ROUND)
                .color(TEXT_MUTED)
                .draw();
        Render2DUtil.text(textX + personSize + 5.0F * scale,
                        UiFonts.sfProDisplay().centeredTextY(labelCenterY, labelSize), labelSize, ACCOUNT_NAME)
                .style(UiFontStyle.MEDIUM)
                .color(TEXT_MUTED)
                .draw();

        // Bottom line: the connected Discord username (not the display name).
        String username = DiscordPresence.INSTANCE.username();
        String handle = username != null && !username.isBlank() ? "@" + username : "Not connected";
        Render2DUtil.text(textX, UiFonts.sfProDisplay().centeredTextY(cardY + cardHeight * 0.66F, handleSize),
                        handleSize, handle)
                .style(UiFontStyle.SEMIBOLD)
                .color(TEXT_PRIMARY)
                .draw();
    }

    /**
     * Client-settings popup on the LEFT of the main panel (mirror of the module-settings
     * panel on the right): Language, Gui Scale, Configs, Themes. Backed by real Setting
     * objects so the dropdown/slider/color-picker machinery is reused wholesale.
     */
    private static void renderClientPanel(GuiGraphicsExtractor guiGraphicsExtractor,
                                          float mainPanelX, float mainPanelY, float mainPanelHeight, float scale) {
        lastClientRows.clear();
        float appear = anim("clientAppear", clientPanelOpen ? 1.0F : 0.0F, clientPanelOpen ? SPEED_APPEAR : SPEED_CLOSE);
        if (appear < 0.01F) {
            lastClientPanelWidth = 0.0F;
            lastClientPanelHeight = 0.0F;
            return;
        }
        rowSink = lastClientRows;

        float padding = SETTINGS_PADDING * scale;
        float panelWidth = CLIENT_WIDTH * scale;
        float rowWidth = panelWidth - padding * 2.0F;
        float slideIn = (1.0F - easeOutCubic(appear)) * SETTINGS_SLIDE_IN * scale;
        float panelX = mainPanelX - SETTINGS_GAP * scale - panelWidth - slideIn;
        float panelY = mainPanelY;

        float headerHeight = SETTINGS_TITLE_SIZE * scale + SETTINGS_TITLE_GAP * scale
                + SETTINGS_SUBTITLE_SIZE * scale * 2.0F + SETTINGS_DIVIDER_GAP_TOP * scale
                + Math.max(0.5F, DIVIDER_THICKNESS * scale) + SETTINGS_DIVIDER_GAP_BOTTOM * scale;
        float rowH = SETTINGS_ROW_HEIGHT * scale;
        float sliderH = SETTINGS_SLIDER_ROW_HEIGHT * scale;
        float gap = SETTINGS_ROW_GAP * scale;
        float dividerThickness = Math.max(0.5F, DIVIDER_THICKNESS * scale);
        // Language, Gui Scale, Themes, Color Mode | hairline | Background Light, Background Embers, Glow.
        float rowsHeight = rowH + gap + sliderH + gap + rowH + gap + rowH + gap
                + dividerThickness + gap + rowH + gap + rowH + gap + rowH + gap;
        float panelHeight = padding * 2.0F + headerHeight + rowsHeight;

        lastClientPanelX = panelX;
        lastClientPanelY = panelY;
        lastClientPanelWidth = panelWidth;
        lastClientPanelHeight = panelHeight;

        float zoom = 0.9F + 0.1F * easeOutCubic(appear);
        var pose = guiGraphicsExtractor.pose();
        pose.pushMatrix();
        pose.translate(panelX, panelY);
        pose.scale(zoom, zoom);
        pose.translate(-panelX, -panelY);

        Render2DUtil.rect(panelX, panelY, panelWidth, panelHeight)
                .color(ColorUtil.withAlpha(MENU_PANEL, PANEL_BLUR_ALPHA))
                .radius(SETTINGS_RADIUS * scale)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), Theme.Colors.PANEL_BORDER)
                .blur(BLUR_RADIUS * scale, 1.0F)
                .draw();

        // Header.
        float cursorY = panelY + padding;
        float gearSize = ACCOUNT_GEAR * scale;
        Render2DUtil.text(panelX + padding, cursorY, SETTINGS_TITLE_SIZE * scale, "Settings Panel")
                .style(UiFontStyle.SEMIBOLD)
                .color(TEXT_PRIMARY)
                .draw();
        Render2DUtil.texture(panelX + panelWidth - padding - gearSize, cursorY, gearSize, gearSize, Textures.Header.SETTINGS)
                .color(TEXT_MUTED)
                .draw();
        cursorY += SETTINGS_TITLE_SIZE * scale + SETTINGS_TITLE_GAP * scale;
        Render2DUtil.text(panelX + padding, cursorY, SETTINGS_SUBTITLE_SIZE * scale, "Customize the GUI appearance, layout")
                .style(UiFontStyle.MEDIUM)
                .color(Theme.Colors.SECTION)
                .draw();
        cursorY += SETTINGS_SUBTITLE_SIZE * scale;
        Render2DUtil.text(panelX + padding, cursorY, SETTINGS_SUBTITLE_SIZE * scale, "and interface preferences.")
                .style(UiFontStyle.MEDIUM)
                .color(Theme.Colors.SECTION)
                .draw();
        cursorY += SETTINGS_SUBTITLE_SIZE * scale + SETTINGS_DIVIDER_GAP_TOP * scale;

        Render2DUtil.rect(panelX + padding, cursorY, rowWidth, dividerThickness).color(HAIRLINE).draw();
        cursorY += dividerThickness + SETTINGS_DIVIDER_GAP_BOTTOM * scale;

        float rowX = panelX + padding;

        // Language (dropdown).
        renderSettingsLabel(rowX, cursorY, rowH, "Language", TEXT_ROW, scale);
        SettingRow langRow = renderDropdownPill(rowX, cursorY, rowWidth, rowH, false, languageSetting, languageSetting.getValue(),
                SettingRowKind.MODE_TOGGLE, scale);
        lastClientRows.add(langRow);
        float langY = cursorY;
        cursorY += rowH + gap;

        // Gui Scale (slider).
        renderClientSlider(rowX, cursorY, rowWidth, sliderH, guiScaleSetting, scale);
        cursorY += sliderH + gap;

        // Themes (custom color swatch -> picker).
        renderSettingsLabel(rowX, cursorY, rowH, "Themes", TEXT_ROW, scale);
        float swatchW = SETTINGS_SWATCH_WIDTH * scale;
        float swatchH = SETTINGS_SWATCH_HEIGHT * scale;
        float swatchX = rowX + rowWidth - swatchW;
        float swatchY = cursorY + (rowH - swatchH) / 2.0F;
        // While a cycle runs the swatch shows the live accent instead of the picked one.
        Render2DUtil.rect(swatchX, swatchY, swatchW, swatchH)
                .color(Theme.isAccentAnimated() ? Theme.getAccent() : themeColorSetting.getValue())
                .radius(SETTINGS_PILL_RADIUS * scale)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), HAIRLINE)
                .draw();
        SettingRow themeRow = new SettingRow(SettingRowKind.COLOR_TOGGLE, themeColorSetting, rowX, cursorY, rowWidth, rowH);
        lastClientRows.add(themeRow);
        cursorY += rowH + gap;

        // Color Mode (dropdown): Static / Rainbow / Astolfo.
        renderSettingsLabel(rowX, cursorY, rowH, accentModeSetting.getName(), TEXT_ROW, scale);
        SettingRow accentModeRow = renderDropdownPill(rowX, cursorY, rowWidth, rowH, false, accentModeSetting,
                accentModeSetting.getValue(), SettingRowKind.MODE_TOGGLE, scale);
        lastClientRows.add(accentModeRow);
        cursorY += rowH + gap;

        // Background section: the ambient layers MenuBackgroundFx paints behind the panels.
        Render2DUtil.rect(rowX, cursorY, rowWidth, dividerThickness).color(SEPARATOR).draw();
        cursorY += dividerThickness + gap;

        renderClientToggle(rowX, cursorY, rowWidth, rowH, backgroundLightSetting, scale);
        cursorY += rowH + gap;
        renderClientToggle(rowX, cursorY, rowWidth, rowH, backgroundEmbersSetting, scale);
        cursorY += rowH + gap;
        renderClientToggle(rowX, cursorY, rowWidth, rowH, glowSetting, scale);

        // Flyouts (float above rows, within this panel's transform).
        if (openDropdown == languageSetting) {
            renderModeFlyout(languageSetting, new SettingRow(SettingRowKind.MODE_TOGGLE, languageSetting, rowX, langY, rowWidth, rowH), scale);
        } else if (openDropdown == themeColorSetting) {
            renderColorFlyout(themeColorSetting, themeRow, scale);
        } else if (openDropdown == accentModeSetting) {
            renderModeFlyout(accentModeSetting, accentModeRow, scale);
        }

        pose.popMatrix();
    }

    /** Label + switch row for the client panel, hit-tested through the shared CHECKBOX row kind. */
    private static void renderClientToggle(float rowX, float rowY, float rowWidth, float rowHeight,
                                           BooleanSetting setting, float scale) {
        renderSettingsLabel(rowX, rowY, rowHeight, setting.getName(), TEXT_ROW, scale);
        float toggleWidth = TOGGLE_WIDTH * scale;
        float toggleHeight = TOGGLE_HEIGHT * scale;
        float toggleX = rowX + rowWidth - toggleWidth;
        float toggleY = rowY + (rowHeight - toggleHeight) / 2.0F;
        renderToggle(toggleX, toggleY, toggleWidth, toggleHeight,
                anim(setting, setting.getValue() ? 1.0F : 0.0F, SPEED_TOGGLE), scale);
        lastClientRows.add(new SettingRow(SettingRowKind.CHECKBOX, setting, rowX, rowY, rowWidth, rowHeight));
    }

    private static void renderClientSlider(float rowX, float rowY, float rowWidth, float rowHeight, NumberSetting numberSetting, float scale) {
        float labelY = UiFonts.sfProDisplay().centeredTextY(rowY + SETTINGS_VALUE_SIZE * scale / 2.0F, SETTINGS_LABEL_SIZE * scale);
        Render2DUtil.text(rowX, labelY, SETTINGS_LABEL_SIZE * scale, numberSetting.getName())
                .style(UiFontStyle.MEDIUM).color(TEXT_ROW).draw();
        Render2DUtil.text(rowX + rowWidth, labelY, SETTINGS_VALUE_SIZE * scale, NumberSetting.format(numberSetting.getValue()) + "x")
                .style(UiFontStyle.MEDIUM).color(TEXT_MUTED).align(TextAlign.RIGHT).draw();

        float trackHeight = SETTINGS_TRACK_HEIGHT * scale;
        float knobSize = SETTINGS_KNOB_DIAMETER * scale;
        float trackY = rowY + rowHeight - knobSize / 2.0F - trackHeight / 2.0F;
        float trackCenterY = trackY + trackHeight / 2.0F;
        boolean sliderHot = draggingSlider == numberSetting;
        Render2DUtil.rect(rowX, trackY, rowWidth, trackHeight)
                .color(ColorUtil.withAlpha(TOGGLE_OFF_COLOR, 0.9F)).radius(trackHeight / 2.0F).draw();
        float progress = anim(numberSetting, (float) numberSetting.getProgress(), sliderHot ? 45.0F : SPEED_TOGGLE);
        if (progress > 0.001F) {
            Render2DUtil.rect(rowX, trackY, Math.max(trackHeight, rowWidth * progress), trackHeight)
                    .color(Theme.getAccent()).radius(trackHeight / 2.0F).draw();
        }
        float knobX = MathUtil.clamp(rowX + rowWidth * progress - knobSize / 2.0F, rowX - knobSize * 0.15F, rowX + rowWidth - knobSize * 0.85F);
        Render2DUtil.rect(knobX, trackCenterY - knobSize / 2.0F, knobSize, knobSize)
                .color(ColorUtil.WHITE).radius(knobSize / 2.0F).draw();

        float trackHitPadding = knobSize / 2.0F + 3.0F * scale;
        lastClientRows.add(new SettingRow(SettingRowKind.SLIDER, numberSetting, null, -1,
                rowX, trackCenterY - trackHitPadding, rowWidth, trackHitPadding * 2.0F));
    }

    private static void renderSearchBar(float panelX, float panelY, float scale, float panelHeight) {
        float sidebarWidth = SIDEBAR_WIDTH * scale;
        float marginX = SEARCH_MARGIN_X * scale;
        float barWidth = sidebarWidth - marginX * 2.0F;
        float barHeight = SEARCH_HEIGHT * scale;
        float barX = panelX + marginX;
        float barY = panelY + panelHeight - SEARCH_MARGIN_BOTTOM * scale - barHeight;
        lastSearchX = barX;
        lastSearchY = barY;
        lastSearchWidth = barWidth;
        lastSearchHeight = barHeight;

        int dimColor = ColorUtil.withAlpha(CATEGORY_BASE, CATEGORY_UNSELECTED_ALPHA);
        float focusT = anim("searchFocus", searchFocused ? 1.0F : 0.0F, SPEED_COLOR);
        int borderColor = mixColor(ColorUtil.withAlpha(CATEGORY_BASE, SEARCH_BORDER_ALPHA), Theme.getAccent(), focusT);
        Render2DUtil.rect(barX, barY, barWidth, barHeight)
                .color(ColorUtil.TRANSPARENT)
                .radius(SEARCH_RADIUS * scale)
                .border(Math.max(0.5F, SEARCH_BORDER_THICKNESS * scale), borderColor)
                .draw();

        float paddingX = SEARCH_PADDING_X * scale;
        float labelSize = SEARCH_LABEL_SIZE * scale;
        float textY = UiFonts.sfProDisplay().centeredTextY(barY + barHeight / 2.0F, labelSize);
        boolean empty = searchQuery.length() == 0;
        String shown = empty ? "search" : searchQuery.toString();
        if (searchFocused && caretVisible()) {
            shown = empty ? "|" : shown + "|";
        }
        Render2DUtil.text(barX + paddingX, textY, labelSize, shown)
                .style(UiFontStyle.MEDIUM)
                .color(empty && !searchFocused ? dimColor : ColorUtil.WHITE)
                .draw();

        float iconSize = SEARCH_ICON_SIZE * scale;
        float iconX = barX + barWidth - paddingX - iconSize;
        float iconY = barY + (barHeight - iconSize) / 2.0F;
        Render2DUtil.texture(iconX, iconY, iconSize, iconSize, Textures.Header.SEARCH)
                .color(mixColor(dimColor, ColorUtil.WHITE, focusT))
                .draw();
    }

    /** Dropdown of name matches under the search bar; picking a row jumps to that module. */
    private static void renderSearchSuggestions(float panelX, float panelY, float scale, float panelHeight) {
        lastSuggestionFeatures.clear();
        if (!searchFocused) {
            return;
        }
        List<Feature> matches = searchMatches();
        if (matches.isEmpty()) {
            return;
        }

        float rowH = SEARCH_SUGGEST_ROW_HEIGHT * scale;
        float width = lastSearchWidth;
        float x = lastSearchX;
        float height = matches.size() * rowH;
        // The search bar sits at the bottom of the sidebar, so the list unrolls upward from it.
        float y = lastSearchY - SEARCH_SUGGEST_GAP * scale - height;
        float radius = SEARCH_SUGGEST_RADIUS * scale;

        float appear = anim("searchSuggest", 1.0F, SPEED_APPEAR);
        Render2DUtil.rect(x, y, width, height)
                .color(ColorUtil.withAlpha(MENU_PANEL, 0.95F * appear))
                .radius(radius)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), Theme.Colors.PANEL_BORDER)
                .draw();

        lastSuggestionX = x;
        lastSuggestionY = y;
        lastSuggestionWidth = width;
        lastSuggestionRowHeight = rowH;

        float paddingX = SEARCH_PADDING_X * scale;
        float labelSize = SEARCH_LABEL_SIZE * scale;
        float catSize = labelSize * 0.82F;
        int accent = Theme.getAccent();
        for (int i = 0; i < matches.size(); i++) {
            Feature feature = matches.get(i);
            float rowY = y + i * rowH;
            boolean hovered = insideRect(hoverMouseX, hoverMouseY, x, rowY, width, rowH);
            if (hovered) {
                Render2DUtil.rect(x + 2.0F * scale, rowY + 2.0F * scale, width - 4.0F * scale, rowH - 4.0F * scale)
                        .color(ColorUtil.withAlpha(accent, 0.15F))
                        .radius(radius - 3.0F * scale)
                        .draw();
            }
            float textY = UiFonts.sfProDisplay().centeredTextY(rowY + rowH / 2.0F, labelSize);
            Render2DUtil.text(x + paddingX, textY, labelSize, feature.getName())
                    .style(UiFontStyle.MEDIUM)
                    .color(hovered ? ColorUtil.WHITE : TEXT_ROW)
                    .draw();
            Render2DUtil.text(x + width - paddingX, UiFonts.sfProDisplay().centeredTextY(rowY + rowH / 2.0F, catSize),
                            catSize, feature.getCategory().getDisplayName())
                    .style(UiFontStyle.MEDIUM)
                    .align(TextAlign.RIGHT)
                    .color(TEXT_MUTED)
                    .draw();
            lastSuggestionFeatures.add(feature);
        }
    }

    /** Jump the grid to the picked module's category and flag it for the highlight sweep. */
    private static void selectFromSearch(Feature feature) {
        selectedCategory = feature.getCategory();
        highlightedFeature = feature;
        highlightUntilNanos = System.nanoTime() + HIGHLIGHT_DURATION_NANOS;
        searchQuery.setLength(0);
        searchFocused = false;
        scrollToFeature(feature);
    }

    private static void scrollToFeature(Feature feature) {
        List<Feature> features = visibleFeatures();
        int index = features.indexOf(feature);
        float scale = lastScale;
        float panelHeight = PANEL_HEIGHT * scale;
        if (index < 0) {
            moduleScrollOffset = 0.0F;
            return;
        }
        int rowIdx = index / MODULE_COLUMNS;
        float cardTop = rowIdx * (MODULE_CARD_HEIGHT * scale + MODULE_CARD_GAP_Y * scale);
        float available = panelHeight - CONTENT_PADDING * scale * 2.0F;
        // Centre the target row in the viewport where possible.
        float target = cardTop - (available - MODULE_CARD_HEIGHT * scale) / 2.0F;
        moduleScrollOffset = MathUtil.clamp(target, 0.0F, moduleScrollMax(scale, panelHeight));
    }

    private static float highlightIntensity() {
        if (highlightedFeature == null) {
            return 0.0F;
        }
        long now = System.nanoTime();
        if (now >= highlightUntilNanos || highlightedFeature.getCategory() != selectedCategory) {
            highlightedFeature = null;
            return 0.0F;
        }
        float remaining = (highlightUntilNanos - now) / 1_000_000_000.0F;
        return MathUtil.clamp(remaining / HIGHLIGHT_FADE_SECONDS, 0.0F, 1.0F);
    }

    private static int scaleAlpha(int color, float factor) {
        float alpha = ColorUtil.alpha(color) / 255.0F * factor;
        return ColorUtil.withAlpha(color, MathUtil.clamp01(alpha));
    }

    // ── module grid ──────────────────────────────────────────────────────────

    // The grid always shows the selected category as usual; search now only drives the
    // suggestion dropdown, which jumps to and highlights the picked module instead of filtering.
    private static List<Feature> visibleFeatures() {
        List<Feature> visible = new ArrayList<>();
        for (Feature feature : FeatureManager.INSTANCE.getFeatures(selectedCategory)) {
            if (feature.isVisible()) {
                visible.add(feature);
            }
        }
        return visible;
    }

    /** Cross-category name matches for the search dropdown, prefix hits ranked first. */
    private static List<Feature> searchMatches() {
        List<Feature> matches = new ArrayList<>();
        String query = searchQuery.toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (query.isEmpty()) {
            return matches;
        }
        for (Feature feature : FeatureManager.INSTANCE.getFeatures()) {
            if (feature.isVisible() && feature.getName().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                matches.add(feature);
            }
        }
        matches.sort((a, b) -> {
            boolean pa = a.getName().toLowerCase(java.util.Locale.ROOT).startsWith(query);
            boolean pb = b.getName().toLowerCase(java.util.Locale.ROOT).startsWith(query);
            if (pa != pb) {
                return pa ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        if (matches.size() > SEARCH_SUGGEST_MAX) {
            return new ArrayList<>(matches.subList(0, SEARCH_SUGGEST_MAX));
        }
        return matches;
    }

    private static float moduleContentHeight(float scale) {
        int count = visibleFeatures().size();
        int rows = (count + MODULE_COLUMNS - 1) / MODULE_COLUMNS;
        if (rows <= 0) {
            return 0.0F;
        }
        return rows * (MODULE_CARD_HEIGHT * scale) + (rows - 1) * (MODULE_CARD_GAP_Y * scale);
    }

    private static float moduleScrollMax(float scale, float panelHeight) {
        float available = panelHeight - CONTENT_PADDING * scale * 2.0F;
        return Math.max(0.0F, moduleContentHeight(scale) - available);
    }

    private static void renderModules(float panelX, float panelY, float scale, float panelHeight) {
        float contentAreaX = panelX + SIDEBAR_WIDTH * scale;
        float contentAreaWidth = PANEL_WIDTH * scale - SIDEBAR_WIDTH * scale;
        float contentX = contentAreaX + CONTENT_PADDING * scale;
        float contentY = panelY + CONTENT_PADDING * scale - moduleScrollShown;
        float contentWidth = contentAreaWidth - CONTENT_PADDING * scale * 2.0F;

        float gapX = MODULE_CARD_GAP_X * scale;
        float gapY = MODULE_CARD_GAP_Y * scale;
        float cardWidth = (contentWidth - gapX * (MODULE_COLUMNS - 1)) / MODULE_COLUMNS;
        float cardHeight = MODULE_CARD_HEIGHT * scale;

        // Clip the grid to the panel; the scroll wheel moves content through the window.
        Render2DUtil.pushScissor(contentAreaX, panelY, contentAreaWidth, panelHeight);

        float highlight = highlightIntensity();

        List<Feature> features = visibleFeatures();
        for (int index = 0; index < features.size(); index++) {
            Feature feature = features.get(index);
            int column = index % MODULE_COLUMNS;
            int rowIdx = index / MODULE_COLUMNS;
            float cardX = contentX + column * (cardWidth + gapX);
            float cardY = contentY + rowIdx * (cardHeight + gapY);

            boolean capturing = feature == capturingBindFeature;
            boolean hovered = insideRect(hoverMouseX, hoverMouseY, cardX, cardY, cardWidth, cardHeight)
                    && hoverMouseY >= panelY && hoverMouseY <= panelY + panelHeight;
            boolean opened = feature == settingsFeature;
            float hoverT = anim(hoverKey(feature), hovered || opened ? 1.0F : 0.0F, SPEED_HOVER);

            // Search jump highlights its target and dims everything else while it fades.
            boolean isHighlighted = highlight > 0.0F && feature == highlightedFeature;
            float dimFactor = highlight > 0.0F && !isHighlighted ? 1.0F - 0.72F * highlight : 1.0F;

            // Outline only — the affordance is the border warming toward the accent, no fill.
            int borderColor = capturing || isHighlighted
                    ? Theme.getAccent()
                    : mixColor(ColorUtil.withAlpha(CATEGORY_BASE, MODULE_CARD_BORDER_ALPHA), Theme.getAccent(), hoverT * 0.85F);
            if (dimFactor < 1.0F) {
                borderColor = scaleAlpha(borderColor, dimFactor);
            }
            Render2DUtil.rect(cardX, cardY, cardWidth, cardHeight)
                    .color(isHighlighted ? ColorUtil.withAlpha(Theme.getAccent(), 0.10F * highlight) : ColorUtil.TRANSPARENT)
                    .radius(MODULE_CARD_RADIUS * scale)
                    .border(Math.max(0.5F, MODULE_CARD_BORDER_THICKNESS * scale), borderColor)
                    .draw();

            float paddingX = MODULE_CARD_PADDING_X * scale;
            float labelSize = MODULE_LABEL_SIZE * scale;
            float textY = UiFonts.sfProDisplay().centeredTextY(cardY + cardHeight / 2.0F, labelSize);
            Render2DUtil.text(cardX + paddingX, textY, labelSize, feature.getName())
                    .style(UiFontStyle.MEDIUM)
                    .color(dimFactor < 1.0F ? scaleAlpha(TEXT_PRIMARY, dimFactor) : TEXT_PRIMARY)
                    .draw();

            float toggleWidth = TOGGLE_WIDTH * scale;
            float toggleHeight = TOGGLE_HEIGHT * scale;
            float toggleX = cardX + cardWidth - paddingX - toggleWidth;
            float toggleY = cardY + (cardHeight - toggleHeight) / 2.0F;
            float toggleT = anim(feature, feature.isEnabled() ? 1.0F : 0.0F, SPEED_TOGGLE);
            renderToggle(toggleX, toggleY, toggleWidth, toggleHeight, toggleT, scale);

            // "…" before the toggle on modules that carry settings — opens the settings popup.
            if (hasSettings(feature)) {
                float dotSize = MODULE_DOT_SIZE * scale;
                float dotsCenterX = toggleX - MODULE_DOTS_GAP * scale - dotSize / 2.0F;
                float cardCenterY = cardY + cardHeight / 2.0F;
                float hit = MODULE_DOTS_HIT * scale;
                boolean dotsOpen = feature == settingsFeature;
                boolean dotsHover = insideRect(hoverMouseX, hoverMouseY,
                        dotsCenterX - hit / 2.0F, cardCenterY - hit / 2.0F, hit, hit)
                        && hoverMouseY >= panelY && hoverMouseY <= panelY + panelHeight;
                float dotsT = anim(dotsAnimKey(feature), dotsOpen || dotsHover ? 1.0F : 0.0F, SPEED_HOVER);
                int dotColor = mixColor(TEXT_MUTED, Theme.getAccent(), dotsT);
                if (dimFactor < 1.0F) {
                    dotColor = scaleAlpha(dotColor, dimFactor);
                }
                float spacing = MODULE_DOT_SPACING * scale;
                for (int d = -1; d <= 1; d++) {
                    Render2DUtil.rect(dotsCenterX + d * spacing - dotSize / 2.0F, cardCenterY - dotSize / 2.0F, dotSize, dotSize)
                            .color(dotColor)
                            .radius(dotSize / 2.0F)
                            .draw();
                }
            }
        }

        if (features.isEmpty()) {
            boolean soon = selectedCategory == FeatureCategory.COSMETICS || selectedCategory == FeatureCategory.SCRIPTS;
            String message = soon ? "SOON" : "Nothing here yet";
            float messageSize = (soon ? 40.0F : 15.0F) * scale;
            float centerX = contentAreaX + contentAreaWidth / 2.0F;
            float centerY = UiFonts.sfProDisplay().centeredTextY(panelY + panelHeight / 2.0F, messageSize);
            Render2DUtil.text(centerX, centerY, messageSize, message)
                    .style(soon ? UiFontStyle.SEMIBOLD : UiFontStyle.MEDIUM)
                    .align(TextAlign.CENTER)
                    .color(soon ? mixColor(TEXT_MUTED, Theme.getAccent(), 0.35F) : TEXT_MUTED)
                    .draw();
        }

        Render2DUtil.popScissor();

        // Scrollbar indicator, only while the category actually overflows.
        float max = moduleScrollMax(scale, panelHeight);
        if (max > 0.5F) {
            float trackTop = panelY + CONTENT_PADDING * scale;
            float trackHeight = panelHeight - CONTENT_PADDING * scale * 2.0F;
            float viewport = trackHeight;
            float total = moduleContentHeight(scale);
            float thumbHeight = Math.max(24.0F * scale, viewport * (viewport / total));
            float thumbY = trackTop + (trackHeight - thumbHeight) * (moduleScrollShown / max);
            float barW = SCROLLBAR_WIDTH * scale;
            float barX = panelX + PANEL_WIDTH * scale - barW - 5.0F * scale;
            Render2DUtil.rect(barX, thumbY, barW, thumbHeight)
                    .color(ColorUtil.withAlpha(CATEGORY_BASE, 0.7F))
                    .radius(barW / 2.0F)
                    .draw();
        }
    }

    // ── settings popup ───────────────────────────────────────────────────────

    /** True when a module exposes at least one real, adjustable setting (bind rows don't count). */
    private static boolean hasSettings(Feature feature) {
        for (Setting<?> setting : feature.getSettings()) {
            if (isSupportedSetting(setting)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupportedSetting(Setting<?> setting) {
        return setting instanceof BooleanSetting
                || setting instanceof ModeSetting
                || setting instanceof NumberSetting
                || setting instanceof ColorSetting
                || setting instanceof MultiSelectSetting
                || setting instanceof TextSetting
                || setting instanceof ButtonSetting
                || setting instanceof InputBindSetting;
    }

    private static boolean rowWraps(String label, float rowWidth, float scale) {
        float labelWidth = UiFonts.sfProDisplay().measureWidth(label, SETTINGS_LABEL_SIZE * scale);
        float available = rowWidth - labelWidth - SETTINGS_PILL_GAP * scale;
        return available < SETTINGS_PILL_MIN_WIDTH * scale;
    }

    private static float settingRowHeight(Setting<?> setting, float rowWidth, float scale) {
        if (setting instanceof NumberSetting) {
            return SETTINGS_SLIDER_ROW_HEIGHT * scale;
        }
        if (setting instanceof MultiSelectSetting multiSetting) {
            return multiSelectRowHeight(multiSetting, rowWidth, scale);
        }
        if (setting instanceof ModeSetting) {
            boolean wraps = rowWraps(setting.getName(), rowWidth, scale);
            return wraps ? SETTINGS_ROW_HEIGHT * scale + SETTINGS_WRAP_EXTRA * scale : SETTINGS_ROW_HEIGHT * scale;
        }
        return SETTINGS_ROW_HEIGHT * scale;
    }

    /** Greedily wraps chip labels into rows that fit {@code rowWidth}, same rule used to render them. */
    private static List<List<String>> wrapChipRows(List<String> options, float rowWidth, float scale) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        float lineWidth = 0.0F;
        for (String option : options) {
            float chipWidth = chipWidth(option, scale);
            float needed = current.isEmpty() ? chipWidth : lineWidth + SETTINGS_CHIP_GAP_X * scale + chipWidth;
            if (!current.isEmpty() && needed > rowWidth) {
                rows.add(current);
                current = new ArrayList<>();
                lineWidth = chipWidth;
            } else {
                lineWidth = needed;
            }
            current.add(option);
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        return rows;
    }

    private static float chipWidth(String option, float scale) {
        return UiFonts.sfProDisplay().measureWidth(option, SETTINGS_VALUE_SIZE * scale) + SETTINGS_CHIP_PADDING_X * scale * 2.0F;
    }

    private static float multiSelectRowHeight(MultiSelectSetting multiSetting, float rowWidth, float scale) {
        List<List<String>> rows = wrapChipRows(multiSetting.getOptions(), rowWidth, scale);
        float chipHeight = SETTINGS_CHIP_HEIGHT * scale;
        float chipsHeight = rows.size() * chipHeight + Math.max(0, rows.size() - 1) * SETTINGS_CHIP_GAP_Y * scale;
        return SETTINGS_CHIP_LABEL_HEIGHT * scale + SETTINGS_CHIP_GAP_Y * scale + chipsHeight;
    }

    /**
     * Renders a multi-select row inline: name + "selected/total" counter on one line, then the
     * option pool as wrapping pill chips underneath — selected chips filled with the accent,
     * the rest muted. No flyout; every chip is directly clickable and toggles on its own.
     */
    private static void renderMultiChips(float rowX, float rowY, float rowWidth, MultiSelectSetting multiSetting,
                                         boolean interactive, float scale) {
        float labelHeight = SETTINGS_CHIP_LABEL_HEIGHT * scale;
        float labelY = UiFonts.sfProDisplay().centeredTextY(rowY + labelHeight / 2.0F, SETTINGS_LABEL_SIZE * scale);
        Render2DUtil.text(rowX, labelY, SETTINGS_LABEL_SIZE * scale, multiSetting.getName())
                .style(UiFontStyle.MEDIUM)
                .color(TEXT_ROW)
                .draw();

        List<String> options = multiSetting.getOptions();
        String counter = multiSetting.getValue().size() + "/" + options.size();
        float counterSize = SETTINGS_VALUE_SIZE * scale;
        float counterY = UiFonts.sfProDisplay().centeredTextY(rowY + labelHeight / 2.0F, counterSize);
        Render2DUtil.text(rowX + rowWidth, counterY, counterSize, counter)
                .style(UiFontStyle.SEMIBOLD)
                .color(Theme.getAccent())
                .align(TextAlign.RIGHT)
                .draw();

        float chipHeight = SETTINGS_CHIP_HEIGHT * scale;
        float cursorY = rowY + labelHeight + SETTINGS_CHIP_GAP_Y * scale;
        for (List<String> line : wrapChipRows(options, rowWidth, scale)) {
            float cursorX = rowX;
            for (String option : line) {
                float width = chipWidth(option, scale);
                boolean selected = multiSetting.isSelected(option);
                boolean hovered = insideRect(hoverMouseX, hoverMouseY, cursorX, cursorY, width, chipHeight);
                int bg = selected
                        ? Theme.getAccent()
                        : ColorUtil.withAlpha(TOGGLE_OFF_COLOR, hovered ? 1.0F : 0.85F);
                int textColor = selected ? ColorUtil.WHITE : (hovered ? TEXT_ROW : TEXT_MUTED);
                Render2DUtil.rect(cursorX, cursorY, width, chipHeight)
                        .color(bg)
                        .radius(chipHeight / 2.0F)
                        .draw();
                float textY = UiFonts.sfProDisplay().centeredTextY(cursorY + chipHeight / 2.0F, SETTINGS_VALUE_SIZE * scale);
                Render2DUtil.text(cursorX + width / 2.0F, textY, SETTINGS_VALUE_SIZE * scale, option)
                        .style(UiFontStyle.MEDIUM)
                        .color(textColor)
                        .align(TextAlign.CENTER)
                        .draw();
                if (interactive) {
                    rowSink.add(new SettingRow(SettingRowKind.MULTI_OPTION, multiSetting, option, -1, cursorX, cursorY, width, chipHeight));
                }
                cursorX += width + SETTINGS_CHIP_GAP_X * scale;
            }
            cursorY += chipHeight + SETTINGS_CHIP_GAP_Y * scale;
        }
    }

    private static void renderSettingsPanel(GuiGraphicsExtractor guiGraphicsExtractor,
                                            float mainPanelX, float mainPanelY, float mainPanelWidth, float mainPanelHeight, float scale) {
        lastSettingsRows.clear();
        rowSink = lastSettingsRows;

        if (settingsFeature != null) {
            renderedSettingsFeature = settingsFeature;
        }
        float appear = anim("settingsAppear", settingsFeature != null ? 1.0F : 0.0F, settingsFeature != null ? SPEED_APPEAR : SPEED_CLOSE);
        if (appear < 0.01F || renderedSettingsFeature == null) {
            lastSettingsPanelWidth = 0.0F;
            lastSettingsPanelHeight = 0.0F;
            return;
        }
        Feature feature = renderedSettingsFeature;

        List<Setting<?>> settings = new ArrayList<>();
        BooleanSetting expanderSetting = null;
        for (Setting<?> setting : feature.getSettings()) {
            if (setting.isVisible() && isSupportedSetting(setting)) {
                settings.add(setting);
                if (setting instanceof BooleanSetting boolSetting && boolSetting.isExpander()) {
                    expanderSetting = boolSetting;
                }
            }
        }

        // One shared "advanced" open amount per feature, driven by its "..." expander row;
        // advancedOnly() rows below it grow/shrink by this instead of popping in/out.
        float advancedOpen = expanderSetting != null
                ? anim(expanderSetting, expanderSetting.getValue() ? 1.0F : 0.0F, SPEED_TOGGLE)
                : 1.0F;

        float padding = SETTINGS_PADDING * scale;
        float slideIn = (1.0F - appear) * SETTINGS_SLIDE_IN * scale;
        float panelX = mainPanelX + mainPanelWidth + SETTINGS_GAP * scale + settingsDragOffsetX + slideIn;
        float panelY = mainPanelY + settingsDragOffsetY;
        float panelWidth = SETTINGS_WIDTH * scale;
        float rowWidth = panelWidth - padding * 2.0F;

        float headerHeight = SETTINGS_TITLE_SIZE * scale + SETTINGS_TITLE_GAP * scale + SETTINGS_SUBTITLE_SIZE * scale
                + SETTINGS_DIVIDER_GAP_TOP * scale + Math.max(0.5F, DIVIDER_THICKNESS * scale) + SETTINGS_DIVIDER_GAP_BOTTOM * scale;
        float rowsHeight = 0.0F;
        for (Setting<?> setting : settings) {
            float openFactor = setting.isAdvancedOnly() ? advancedOpen : 1.0F;
            rowsHeight += (settingRowHeight(setting, rowWidth, scale) + SETTINGS_ROW_GAP * scale) * openFactor;
        }

        // Modules like Aura carry more settings than fit vertically; cap the popup at the
        // main panel's height and scroll the rows region (header stays pinned).
        float fullHeight = padding * 2.0F + headerHeight + rowsHeight;
        float panelHeight = Math.min(fullHeight, mainPanelHeight);
        float rowsTop = panelY + padding + headerHeight;
        float rowsViewport = panelY + panelHeight - padding - rowsTop;
        float settingsScrollMax = Math.max(0.0F, rowsHeight - rowsViewport);
        if (settingsScrollFeature != feature) {
            settingsScrollFeature = feature;
            settingsScrollOffset = 0.0F;
        }
        settingsScrollOffset = MathUtil.clamp(settingsScrollOffset, 0.0F, settingsScrollMax);
        settingsScrollShown = anim("settingsScroll", settingsScrollOffset, SPEED_SCROLL);
        lastSettingsScrollMax = settingsScrollMax;

        lastSettingsPanelX = panelX;
        lastSettingsPanelY = panelY;
        lastSettingsPanelWidth = panelWidth;
        lastSettingsPanelHeight = panelHeight;

        // Its own pop-in on top of the whole-GUI zoom: scales up from the top-right corner
        // it hinges on, easing out as it settles.
        float settingsZoom = 0.9F + 0.1F * easeOutCubic(appear);
        var pose = guiGraphicsExtractor.pose();
        pose.pushMatrix();
        pose.translate(panelX + panelWidth, panelY);
        pose.scale(settingsZoom, settingsZoom);
        pose.translate(-(panelX + panelWidth), -panelY);

        // Frosted backdrop, same treatment as the main panel.
        Render2DUtil.rect(panelX, panelY, panelWidth, panelHeight)
                .color(ColorUtil.withAlpha(MENU_PANEL, PANEL_BLUR_ALPHA))
                .radius(SETTINGS_RADIUS * scale)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), Theme.Colors.PANEL_BORDER)
                .blur(BLUR_RADIUS * scale, 1.0F)
                .draw();

        float cursorY = panelY + padding;
        Render2DUtil.text(panelX + padding, cursorY, SETTINGS_TITLE_SIZE * scale, feature.getName())
                .style(UiFontStyle.SEMIBOLD)
                .color(TEXT_PRIMARY)
                .draw();
        cursorY += SETTINGS_TITLE_SIZE * scale + SETTINGS_TITLE_GAP * scale;

        int dimColor = ColorUtil.withAlpha(CATEGORY_BASE, CATEGORY_UNSELECTED_ALPHA);
        // Clickable meta line: click to (re)bind the whole feature, same as MMB on its card.
        boolean capturingHeader = capturingBindFeature == feature;
        String bindLabel = ("BIND / " + (capturingHeader ? "..." : feature.getBind().getDisplayValue()))
                .toUpperCase(java.util.Locale.ROOT);
        float bindLabelSize = SETTINGS_SUBTITLE_SIZE * scale;
        Render2DUtil.text(panelX + padding, cursorY, bindLabelSize, bindLabel)
                .style(UiFontStyle.MEDIUM)
                .color(capturingHeader ? Theme.getAccent() : Theme.Colors.SECTION)
                .draw();
        if (settingsFeature != null) {
            float bindLabelWidth = UiFonts.sfProDisplay().measureWidth(bindLabel, bindLabelSize);
            lastSettingsRows.add(new SettingRow(SettingRowKind.BIND_HEADER, null, panelX + padding,
                    cursorY - 2.0F * scale, Math.max(bindLabelWidth, 60.0F * scale), bindLabelSize + 6.0F * scale));
        }
        cursorY += SETTINGS_SUBTITLE_SIZE * scale + SETTINGS_DIVIDER_GAP_TOP * scale;

        float dividerThickness = Math.max(0.5F, DIVIDER_THICKNESS * scale);
        Render2DUtil.rect(panelX + padding, cursorY, rowWidth, dividerThickness)
                .color(HAIRLINE)
                .draw();

        // Rows scroll inside a clipped viewport under the pinned header.
        float rowsBottom = rowsTop + rowsViewport;
        Render2DUtil.pushScissor(panelX, rowsTop, panelWidth, rowsViewport);
        cursorY = rowsTop - settingsScrollShown;

        boolean interactive = settingsFeature != null;
        SettingRow openFlyoutRow = null;
        for (int s = 0; s < settings.size(); s++) {
            Setting<?> setting = settings.get(s);
            float rowHeight = settingRowHeight(setting, rowWidth, scale);
            boolean advancedRow = setting.isAdvancedOnly();
            float openFactor = advancedRow ? advancedOpen : 1.0F;
            float effectiveHeight = rowHeight * openFactor;
            float rowY = cursorY;
            float rowX = panelX + padding;
            boolean rowInteractive = interactive && (!advancedRow || advancedOpen > 0.5F);
            boolean rowCollapsed = advancedRow && effectiveHeight < 0.5F * scale;

            if (!rowCollapsed) {
                // Hairline separator between consecutive rows, as in the Figma reference.
                if (s > 0) {
                    Render2DUtil.rect(rowX, rowY - SETTINGS_ROW_GAP * scale / 2.0F, rowWidth, Math.max(0.5F, DIVIDER_THICKNESS * scale))
                            .color(SEPARATOR)
                            .draw();
                }

                // Advanced rows grow/shrink from a clipped 0-height sliver into their full
                // row height as the "..." expander opens/closes, instead of popping in/out.
                if (advancedRow) {
                    Render2DUtil.pushScissor(rowX - padding, rowY, rowWidth + padding * 2.0F, Math.max(0.0F, effectiveHeight));
                }

                if (setting instanceof BooleanSetting boolSetting) {
                    renderSettingsLabel(rowX, rowY, rowHeight, setting.getName(), TEXT_ROW, scale);
                    if (boolSetting.isExpander()) {
                        renderExpanderDots(rowX, rowY, rowWidth, rowHeight, advancedOpen, scale);
                    } else {
                        float toggleWidth = TOGGLE_WIDTH * scale;
                        float toggleHeight = TOGGLE_HEIGHT * scale;
                        float toggleX = rowX + rowWidth - toggleWidth;
                        float toggleY = rowY + (rowHeight - toggleHeight) / 2.0F;
                        float toggleT = anim(setting, boolSetting.getValue() ? 1.0F : 0.0F, SPEED_TOGGLE);
                        renderToggle(toggleX, toggleY, toggleWidth, toggleHeight, toggleT, scale);
                    }
                    if (rowInteractive) {
                        lastSettingsRows.add(new SettingRow(SettingRowKind.CHECKBOX, setting, rowX, rowY, rowWidth, rowHeight));
                    }
                } else if (setting instanceof ModeSetting modeSetting) {
                    boolean wraps = rowWraps(setting.getName(), rowWidth, scale);
                    renderSettingsLabel(rowX, rowY, wraps ? SETTINGS_ROW_HEIGHT * scale : rowHeight, setting.getName(), TEXT_ROW, scale);
                    SettingRow row = renderDropdownPill(rowX, rowY, rowWidth, rowHeight, wraps, modeSetting, modeSetting.getValue(),
                            SettingRowKind.MODE_TOGGLE, scale);
                    if (rowInteractive) {
                        lastSettingsRows.add(row);
                    }
                    if (openDropdown == modeSetting) {
                        openFlyoutRow = row;
                    }
                } else if (setting instanceof MultiSelectSetting multiSetting) {
                    renderMultiChips(rowX, rowY, rowWidth, multiSetting, rowInteractive, scale);
                } else if (setting instanceof NumberSetting numberSetting) {
                    float labelY = UiFonts.sfProDisplay().centeredTextY(rowY + SETTINGS_VALUE_SIZE * scale / 2.0F, SETTINGS_LABEL_SIZE * scale);
                    Render2DUtil.text(rowX, labelY, SETTINGS_LABEL_SIZE * scale, setting.getName())
                            .style(UiFontStyle.MEDIUM)
                            .color(TEXT_ROW)
                            .draw();
                    String valueText = NumberSetting.format(numberSetting.getValue()) + " / " + NumberSetting.format(numberSetting.getMax());
                    Render2DUtil.text(rowX + rowWidth, labelY, SETTINGS_VALUE_SIZE * scale, valueText)
                            .style(UiFontStyle.MEDIUM)
                            .color(TEXT_MUTED)
                            .align(TextAlign.RIGHT)
                            .draw();

                    float trackHeight = SETTINGS_TRACK_HEIGHT * scale;
                    float knobSize = SETTINGS_KNOB_DIAMETER * scale;
                    float trackY = rowY + rowHeight - knobSize / 2.0F - trackHeight / 2.0F;
                    float trackCenterY = trackY + trackHeight / 2.0F;
                    boolean sliderHot = draggingSlider == setting;

                    Render2DUtil.rect(rowX, trackY, rowWidth, trackHeight)
                            .color(ColorUtil.withAlpha(TOGGLE_OFF_COLOR, 0.9F))
                            .radius(trackHeight / 2.0F)
                            .draw();
                    // Smoothly follow the value so keyboard/click jumps glide instead of snapping.
                    float progress = anim(setting, (float) numberSetting.getProgress(), sliderHot ? 45.0F : SPEED_TOGGLE);
                    if (progress > 0.001F) {
                        Render2DUtil.rect(rowX, trackY, Math.max(trackHeight, rowWidth * progress), trackHeight)
                                .color(Theme.getAccent())
                                .radius(trackHeight / 2.0F)
                                .draw();
                    }
                    float knobX = MathUtil.clamp(rowX + rowWidth * progress - knobSize / 2.0F, rowX - knobSize * 0.15F, rowX + rowWidth - knobSize * 0.85F);
                    float knobY = trackCenterY - knobSize / 2.0F;
                    float knobScale = sliderHot ? 1.12F : 1.0F;
                    float grown = knobSize * anim(pillAnimKey(setting), knobScale, SPEED_HOVER);
                    Render2DUtil.rect(knobX - (grown - knobSize) / 2.0F, knobY - (grown - knobSize) / 2.0F, grown, grown)
                            .color(ColorUtil.WHITE)
                            .radius(grown / 2.0F)
                            .draw();

                    if (rowInteractive) {
                        float trackHitPadding = knobSize / 2.0F + 3.0F * scale;
                        lastSettingsRows.add(new SettingRow(SettingRowKind.SLIDER, setting, null, -1,
                                rowX, trackCenterY - trackHitPadding, rowWidth, trackHitPadding * 2.0F));
                    }
                } else if (setting instanceof ColorSetting colorSetting) {
                    renderSettingsLabel(rowX, rowY, rowHeight, setting.getName(), TEXT_ROW, scale);
                    float swatchWidth = SETTINGS_SWATCH_WIDTH * scale;
                    float swatchHeight = SETTINGS_SWATCH_HEIGHT * scale;
                    float swatchX = rowX + rowWidth - swatchWidth;
                    float swatchY = rowY + (rowHeight - swatchHeight) / 2.0F;
                    Render2DUtil.rect(swatchX, swatchY, swatchWidth, swatchHeight)
                            .color(colorSetting.getValue())
                            .radius(SETTINGS_PILL_RADIUS * scale)
                            .draw();
                    SettingRow row = new SettingRow(SettingRowKind.COLOR_TOGGLE, setting, rowX, rowY, rowWidth, rowHeight);
                    if (rowInteractive) {
                        lastSettingsRows.add(row);
                    }
                    if (openDropdown == colorSetting) {
                        openFlyoutRow = row;
                    }
                } else if (setting instanceof TextSetting textSetting) {
                    renderSettingsLabel(rowX, rowY, rowHeight, setting.getName(), TEXT_ROW, scale);
                    float fieldWidth = rowWidth * 0.5F;
                    float fieldHeight = SETTINGS_PILL_HEIGHT * scale;
                    float fieldX = rowX + rowWidth - fieldWidth;
                    float fieldY = rowY + (rowHeight - fieldHeight) / 2.0F;
                    boolean focused = focusedTextSetting == textSetting;
                    float focusT = anim(textSetting, focused ? 1.0F : 0.0F, SPEED_COLOR);
                    Render2DUtil.rect(fieldX, fieldY, fieldWidth, fieldHeight)
                            .color(ColorUtil.TRANSPARENT)
                            .radius(SETTINGS_PILL_RADIUS * scale)
                            .border(Math.max(0.5F, 1.0F * scale), mixColor(ColorUtil.withAlpha(CATEGORY_BASE, 0.7F), Theme.getAccent(), focusT))
                            .draw();
                    String value = textSetting.isSecret() ? "*".repeat(textSetting.getValue().length()) : textSetting.getValue();
                    if (focused && caretVisible()) {
                        value = value + "|";
                    }
                    float fieldTextY = UiFonts.sfProDisplay().centeredTextY(fieldY + fieldHeight / 2.0F, SETTINGS_VALUE_SIZE * scale);
                    Render2DUtil.text(fieldX + SETTINGS_PILL_PADDING_X * scale, fieldTextY, SETTINGS_VALUE_SIZE * scale, value)
                            .style(UiFontStyle.MEDIUM)
                            .color(TEXT_PRIMARY)
                            .draw();
                    if (rowInteractive) {
                        lastSettingsRows.add(new SettingRow(SettingRowKind.TEXT_FIELD, setting, fieldX, fieldY, fieldWidth, fieldHeight));
                    }
                } else if (setting instanceof ButtonSetting buttonSetting) {
                    float buttonHeight = SETTINGS_PILL_HEIGHT * scale;
                    float buttonWidth = Math.max(SETTINGS_PILL_MIN_WIDTH * scale,
                            UiFonts.sfProDisplay().measureWidth(buttonSetting.getButtonLabel(), SETTINGS_VALUE_SIZE * scale) + SETTINGS_BUTTON_PADDING_X * scale * 2.0F);
                    float buttonX = rowX + rowWidth - buttonWidth;
                    float buttonY = rowY + (rowHeight - buttonHeight) / 2.0F;
                    boolean hovered = insideRect(hoverMouseX, hoverMouseY, buttonX, buttonY, buttonWidth, buttonHeight);
                    float hoverT = anim(buttonSetting, hovered ? 1.0F : 0.0F, SPEED_HOVER);
                    Render2DUtil.rect(buttonX, buttonY, buttonWidth, buttonHeight)
                            .color(mixColor(Theme.getAccent(), ColorUtil.WHITE, hoverT * 0.18F))
                            .radius(SETTINGS_PILL_RADIUS * scale)
                            .draw();
                    float buttonTextY = UiFonts.sfProDisplay().centeredTextY(buttonY + buttonHeight / 2.0F, SETTINGS_VALUE_SIZE * scale);
                    Render2DUtil.text(buttonX + buttonWidth / 2.0F, buttonTextY, SETTINGS_VALUE_SIZE * scale, buttonSetting.getButtonLabel())
                            .style(UiFontStyle.MEDIUM)
                            .align(TextAlign.CENTER)
                            .color(ColorUtil.WHITE)
                            .draw();
                    if (rowInteractive) {
                        lastSettingsRows.add(new SettingRow(SettingRowKind.BUTTON, setting, buttonX, buttonY, buttonWidth, buttonHeight));
                    }
                } else if (setting instanceof InputBindSetting bindSetting) {
                    renderSettingsLabel(rowX, rowY, rowHeight, setting.getName(), TEXT_ROW, scale);
                    boolean capturing = capturingBindInput == bindSetting;
                    String bindText = capturing ? "..." : bindSetting.getDisplayValue().toUpperCase(java.util.Locale.ROOT);
                    float bindHeight = SETTINGS_PILL_HEIGHT * scale;
                    float bindWidth = Math.max(SETTINGS_PILL_MIN_WIDTH * scale * 0.7F,
                            UiFonts.sfProDisplay().measureWidth(bindText, SETTINGS_VALUE_SIZE * scale) + SETTINGS_PILL_PADDING_X * scale * 2.0F);
                    float bindX = rowX + rowWidth - bindWidth;
                    float bindY = rowY + (rowHeight - bindHeight) / 2.0F;
                    Render2DUtil.rect(bindX, bindY, bindWidth, bindHeight)
                            .color(ColorUtil.TRANSPARENT)
                            .radius(SETTINGS_PILL_RADIUS * scale)
                            .border(Math.max(0.5F, 1.0F * scale),
                                    capturing ? Theme.getAccent() : ColorUtil.withAlpha(CATEGORY_BASE, 0.85F))
                            .draw();
                    float bindTextY = UiFonts.sfProDisplay().centeredTextY(bindY + bindHeight / 2.0F, SETTINGS_VALUE_SIZE * scale);
                    Render2DUtil.text(bindX + bindWidth / 2.0F, bindTextY, SETTINGS_VALUE_SIZE * scale, bindText)
                            .style(UiFontStyle.MEDIUM)
                            .align(TextAlign.CENTER)
                            .color(capturing ? Theme.getAccent() : TEXT_PRIMARY)
                            .draw();
                    if (rowInteractive) {
                        lastSettingsRows.add(new SettingRow(SettingRowKind.BIND_FIELD, setting, bindX, bindY, bindWidth, bindHeight));
                    }
                }

                if (advancedRow) {
                    Render2DUtil.popScissor();
                }
            }

            cursorY += (rowHeight + SETTINGS_ROW_GAP * scale) * openFactor;
        }

        Render2DUtil.popScissor();

        // Drop click targets for rows scrolled out of the viewport so they can't be hit
        // through the pinned header or below the panel.
        final float bandTop = rowsTop;
        final float bandBottom = rowsBottom;
        lastSettingsRows.removeIf(r -> r.kind() != SettingRowKind.MODE_OPTION
                && r.kind() != SettingRowKind.COLOR_SV
                && r.kind() != SettingRowKind.COLOR_HUE
                && r.kind() != SettingRowKind.BIND_HEADER
                && (r.y() + r.height() / 2.0F < bandTop || r.y() + r.height() / 2.0F > bandBottom));

        // Settings scrollbar.
        if (settingsScrollMax > 0.5F) {
            float thumbHeight = Math.max(24.0F * scale, rowsViewport * (rowsViewport / rowsHeight));
            float thumbY = rowsTop + (rowsViewport - thumbHeight) * (settingsScrollShown / settingsScrollMax);
            float barW = SCROLLBAR_WIDTH * scale;
            float barX = panelX + panelWidth - barW - 4.0F * scale;
            Render2DUtil.rect(barX, thumbY, barW, thumbHeight)
                    .color(ColorUtil.withAlpha(CATEGORY_BASE, 0.7F))
                    .radius(barW / 2.0F)
                    .draw();
        }

        // Flyouts float above the clipped rows; only open when their anchor is on-screen.
        if (openFlyoutRow != null && interactive
                && openFlyoutRow.y() + openFlyoutRow.height() / 2.0F >= rowsTop
                && openFlyoutRow.y() + openFlyoutRow.height() / 2.0F <= rowsBottom) {
            if (openFlyoutRow.setting() instanceof ModeSetting modeSetting) {
                renderModeFlyout(modeSetting, openFlyoutRow, scale);
            } else if (openFlyoutRow.setting() instanceof ColorSetting colorSetting) {
                renderColorFlyout(colorSetting, openFlyoutRow, scale);
            }
        }

        pose.popMatrix();
    }

    private static SettingRow renderDropdownPill(float rowX, float rowY, float rowWidth, float rowHeight, boolean wraps,
                                                 Setting<?> setting, String valueText, SettingRowKind kind, float scale) {
        float pillHeight = SETTINGS_PILL_HEIGHT * scale;
        float measured = UiFonts.sfProDisplay().measureWidth(valueText, SETTINGS_VALUE_SIZE * scale) + SETTINGS_PILL_PADDING_X * scale * 2.0F;
        float minWidth = SETTINGS_PILL_MIN_WIDTH * scale;
        float maxWidth = wraps ? rowWidth : rowWidth * 0.55F;
        float pillWidth = MathUtil.clamp(Math.max(measured, minWidth), minWidth, Math.max(minWidth, maxWidth));

        float openT = anim(pillAnimKey(setting), openDropdown == setting ? 1.0F : 0.0F, SPEED_COLOR);
        float pillX = rowX + rowWidth - pillWidth;
        float pillY = wraps ? rowY + SETTINGS_ROW_HEIGHT * scale : rowY + (rowHeight - pillHeight) / 2.0F;

        // Outline pill; the border warms to the accent while its flyout is open.
        Render2DUtil.rect(pillX, pillY, pillWidth, pillHeight)
                .color(ColorUtil.TRANSPARENT)
                .radius(SETTINGS_PILL_RADIUS * scale)
                .border(Math.max(0.5F, 1.0F * scale), mixColor(ColorUtil.withAlpha(CATEGORY_BASE, 0.85F), Theme.getAccent(), openT))
                .draw();
        float pillTextY = UiFonts.sfProDisplay().centeredTextY(pillY + pillHeight / 2.0F, SETTINGS_VALUE_SIZE * scale);
        Render2DUtil.text(pillX + SETTINGS_PILL_PADDING_X * scale, pillTextY, SETTINGS_VALUE_SIZE * scale, valueText)
                .style(UiFontStyle.MEDIUM)
                .color(TEXT_PRIMARY)
                .draw();

        return new SettingRow(kind, setting, rowX, rowY, rowWidth, rowHeight);
    }

    /**
     * The "..." row that reveals a feature's {@link Setting#isAdvancedOnly()} settings.
     * The three dots spread from a horizontal row into a vertical column as {@code openT}
     * goes 0 -> 1, echoing the accordion opening below it instead of a plain checkbox.
     */
    private static void renderExpanderDots(float rowX, float rowY, float rowWidth, float rowHeight, float openT, float scale) {
        float size = TOGGLE_HEIGHT * scale;
        float pillX = rowX + rowWidth - size;
        float pillY = rowY + (rowHeight - size) / 2.0F;
        Render2DUtil.rect(pillX, pillY, size, size)
                .color(mixColor(TOGGLE_OFF_COLOR, Theme.getAccent(), openT))
                .radius(size / 2.0F)
                .draw();

        float centerX = pillX + size / 2.0F;
        float centerY = pillY + size / 2.0F;
        float spacing = size * 0.22F;
        float dotSize = Math.max(1.6F * scale, size * 0.12F);
        for (int i = -1; i <= 1; i++) {
            float dx = centerX + spacing * i * (1.0F - openT);
            float dy = centerY + spacing * i * openT;
            Render2DUtil.rect(dx - dotSize / 2.0F, dy - dotSize / 2.0F, dotSize, dotSize)
                    .color(ColorUtil.WHITE)
                    .radius(dotSize / 2.0F)
                    .draw();
        }
    }

    private static void renderSettingsLabel(float rowX, float rowY, float rowHeight, String name, int color, float scale) {
        float labelY = UiFonts.sfProDisplay().centeredTextY(rowY + rowHeight / 2.0F, SETTINGS_LABEL_SIZE * scale);
        Render2DUtil.text(rowX, labelY, SETTINGS_LABEL_SIZE * scale, name)
                .style(UiFontStyle.MEDIUM)
                .color(color)
                .draw();
    }

    private static void renderToggle(float x, float y, float width, float height, float t, float scale) {
        Render2DUtil.rect(x, y, width, height)
                .color(mixColor(TOGGLE_OFF_COLOR, Theme.getAccent(), t))
                .radius(height / 2.0F)
                .draw();
        float knobPadding = TOGGLE_KNOB_PADDING * scale;
        float knobSize = height - knobPadding * 2.0F;
        float offX = x + knobPadding;
        float onX = x + width - knobSize - knobPadding;
        float knobX = offX + (onX - offX) * t;
        float knobY = y + knobPadding;
        Render2DUtil.rect(knobX, knobY, knobSize, knobSize)
                .color(ColorUtil.WHITE)
                .radius(knobSize / 2.0F)
                .draw();
    }

    private static void renderModeFlyout(ModeSetting modeSetting, SettingRow anchorRow, float scale) {
        List<String> options = modeSetting.getVisibleModes();
        float optionHeight = SETTINGS_OPTION_ROW_HEIGHT * scale;
        float fullHeight = optionHeight * options.size();
        float openT = anim(flyoutAnimKey(modeSetting), 1.0F, SPEED_FLYOUT);
        float flyoutY = anchorRow.y() + anchorRow.height() + 4.0F * scale;
        float flyoutX = anchorRow.x();
        float flyoutWidth = anchorRow.width();

        Render2DUtil.pushScissor(flyoutX, flyoutY, flyoutWidth, fullHeight * openT + 1.0F);
        Render2DUtil.rect(flyoutX, flyoutY, flyoutWidth, fullHeight)
                .color(ColorUtil.withAlpha(MENU_PANEL, PANEL_ALPHA))
                .radius(SETTINGS_PILL_RADIUS * scale)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), HAIRLINE)
                .draw();

        for (int index = 0; index < options.size(); index++) {
            String option = options.get(index);
            float optionY = flyoutY + optionHeight * index;
            boolean selected = modeSetting.is(option);
            boolean hovered = insideRect(hoverMouseX, hoverMouseY, flyoutX, optionY, flyoutWidth, optionHeight);
            int textColor = selected ? TEXT_PRIMARY
                    : mixColor(TEXT_MUTED, TEXT_ROW, hovered ? 1.0F : 0.0F);
            float textY = UiFonts.sfProDisplay().centeredTextY(optionY + optionHeight / 2.0F, SETTINGS_VALUE_SIZE * scale);
            Render2DUtil.text(flyoutX + SETTINGS_PILL_PADDING_X * scale, textY, SETTINGS_VALUE_SIZE * scale, option)
                    .style(UiFontStyle.MEDIUM)
                    .color(textColor)
                    .draw();
            rowSink.add(new SettingRow(SettingRowKind.MODE_OPTION, modeSetting, option, -1, flyoutX, optionY, flyoutWidth, optionHeight));
        }
        Render2DUtil.popScissor();
    }

    /**
     * A real HSV picker: a saturation/value square (built from a white→hue horizontal
     * gradient overlaid with a transparent→black vertical gradient — the same construction
     * CSS pickers use) plus a rainbow hue strip below. Both handles drag continuously.
     */
    private static void renderColorFlyout(ColorSetting colorSetting, SettingRow anchorRow, float scale) {
        float pad = COLOR_PICKER_PADDING * scale;
        float svSize = COLOR_PICKER_SV_SIZE * scale;
        float hueHeight = COLOR_PICKER_HUE_HEIGHT * scale;
        float gap = COLOR_PICKER_GAP * scale;
        float boxWidth = svSize + pad * 2.0F;
        float boxHeight = pad * 2.0F + svSize + gap + hueHeight;

        float boxX = anchorRow.x() + anchorRow.width() - boxWidth;
        float boxY = anchorRow.y() + anchorRow.height() + 4.0F * scale;
        float openT = anim(flyoutAnimKey(colorSetting), 1.0F, SPEED_FLYOUT);

        float[] hsv = ColorUtil.hsv(colorSetting.getValue());
        int hueColor = ColorUtil.fromHsv(hsv[0], 1.0F, 1.0F, 255);

        float svX = boxX + pad;
        float svY = boxY + pad;
        float hueX = svX;
        float hueY = svY + svSize + gap;
        lastSvX = svX;
        lastSvY = svY;
        lastSvSize = svSize;
        lastHueX = hueX;
        lastHueY = hueY;
        lastHueWidth = svSize;
        lastHueHeight = hueHeight;

        Render2DUtil.pushScissor(boxX, boxY, boxWidth, boxHeight * openT + 1.0F);

        Render2DUtil.rect(boxX, boxY, boxWidth, boxHeight)
                .color(ColorUtil.withAlpha(MENU_PANEL, PANEL_ALPHA))
                .radius(SETTINGS_PILL_RADIUS * scale)
                .border(Math.max(0.5F, DIVIDER_THICKNESS * scale), HAIRLINE)
                .draw();

        float svRadius = 5.0F * scale;
        // Base: white (left) -> pure hue (right).
        Render2DUtil.rect(svX, svY, svSize, svSize)
                .color(ColorUtil.WHITE, hueColor, hueColor, ColorUtil.WHITE)
                .radius(svRadius)
                .draw();
        // Overlay: transparent (top) -> black (bottom), darkening toward the bottom.
        int blackClear = ColorUtil.withAlpha(ColorUtil.BLACK, 0);
        Render2DUtil.rect(svX, svY, svSize, svSize)
                .color(blackClear, blackClear, ColorUtil.BLACK, ColorUtil.BLACK)
                .radius(svRadius)
                .draw();

        // SV handle.
        float handleR = COLOR_PICKER_HANDLE * scale;
        float handleX = svX + hsv[1] * svSize;
        float handleY = svY + (1.0F - hsv[2]) * svSize;
        Render2DUtil.rect(handleX - handleR, handleY - handleR, handleR * 2.0F, handleR * 2.0F)
                .color(ColorUtil.TRANSPARENT)
                .radius(handleR)
                .border(Math.max(1.0F, 2.0F * scale), ColorUtil.WHITE)
                .draw();

        // Hue strip: seven stops interpolated pairwise across the spectrum.
        int segments = HUE_STOPS.length - 1;
        float segWidth = svSize / segments;
        for (int i = 0; i < segments; i++) {
            int c0 = HUE_STOPS[i];
            int c1 = HUE_STOPS[i + 1];
            Render2DUtil.rect(hueX + i * segWidth, hueY, segWidth + 0.5F, hueHeight)
                    .color(c0, c1, c1, c0)
                    .draw();
        }
        // Hue handle.
        float hueHandleX = hueX + hsv[0] * svSize;
        float hueHandleW = 4.0F * scale;
        Render2DUtil.rect(hueHandleX - hueHandleW / 2.0F, hueY - 2.0F * scale, hueHandleW, hueHeight + 4.0F * scale)
                .color(ColorUtil.TRANSPARENT)
                .radius(hueHandleW / 2.0F)
                .border(Math.max(1.0F, 2.0F * scale), ColorUtil.WHITE)
                .draw();

        Render2DUtil.popScissor();

        rowSink.add(new SettingRow(SettingRowKind.COLOR_SV, colorSetting, null, -1, svX, svY, svSize, svSize));
        rowSink.add(new SettingRow(SettingRowKind.COLOR_HUE, colorSetting, null, -1,
                hueX, hueY - 3.0F * scale, svSize, hueHeight + 6.0F * scale));
    }

    // ── hit testing ──────────────────────────────────────────────────────────

    private static Feature moduleCardAt(float panelX, float panelY, float scale, int mouseX, int mouseY) {
        float contentX = panelX + SIDEBAR_WIDTH * scale + CONTENT_PADDING * scale;
        float contentY = panelY + CONTENT_PADDING * scale - moduleScrollShown;
        float contentWidth = PANEL_WIDTH * scale - SIDEBAR_WIDTH * scale - CONTENT_PADDING * scale * 2.0F;

        float gapX = MODULE_CARD_GAP_X * scale;
        float gapY = MODULE_CARD_GAP_Y * scale;
        float cardWidth = (contentWidth - gapX * (MODULE_COLUMNS - 1)) / MODULE_COLUMNS;
        float cardHeight = MODULE_CARD_HEIGHT * scale;

        if (mouseY < panelY || mouseY > panelY + PANEL_HEIGHT * scale) {
            return null;
        }

        List<Feature> features = visibleFeatures();
        for (int index = 0; index < features.size(); index++) {
            int column = index % MODULE_COLUMNS;
            int row = index / MODULE_COLUMNS;
            float cardX = contentX + column * (cardWidth + gapX);
            float cardY = contentY + row * (cardHeight + gapY);
            if (mouseX >= cardX && mouseX <= cardX + cardWidth && mouseY >= cardY && mouseY <= cardY + cardHeight) {
                return features.get(index);
            }
        }
        return null;
    }

    /** The module whose "…" affordance is under the cursor, or null. Mirrors {@link #renderModules} geometry. */
    private static Feature moduleDotsAt(float panelX, float panelY, float scale, int mouseX, int mouseY) {
        float contentX = panelX + SIDEBAR_WIDTH * scale + CONTENT_PADDING * scale;
        float contentY = panelY + CONTENT_PADDING * scale - moduleScrollShown;
        float contentWidth = PANEL_WIDTH * scale - SIDEBAR_WIDTH * scale - CONTENT_PADDING * scale * 2.0F;

        float gapX = MODULE_CARD_GAP_X * scale;
        float gapY = MODULE_CARD_GAP_Y * scale;
        float cardWidth = (contentWidth - gapX * (MODULE_COLUMNS - 1)) / MODULE_COLUMNS;
        float cardHeight = MODULE_CARD_HEIGHT * scale;
        float paddingX = MODULE_CARD_PADDING_X * scale;
        float toggleWidth = TOGGLE_WIDTH * scale;
        float dotSize = MODULE_DOT_SIZE * scale;
        float hit = MODULE_DOTS_HIT * scale;

        if (mouseY < panelY || mouseY > panelY + PANEL_HEIGHT * scale) {
            return null;
        }

        List<Feature> features = visibleFeatures();
        for (int index = 0; index < features.size(); index++) {
            Feature feature = features.get(index);
            if (!hasSettings(feature)) {
                continue;
            }
            int column = index % MODULE_COLUMNS;
            int row = index / MODULE_COLUMNS;
            float cardX = contentX + column * (cardWidth + gapX);
            float cardY = contentY + row * (cardHeight + gapY);
            float toggleX = cardX + cardWidth - paddingX - toggleWidth;
            float dotsCenterX = toggleX - MODULE_DOTS_GAP * scale - dotSize / 2.0F;
            float cardCenterY = cardY + cardHeight / 2.0F;
            if (insideRect(mouseX, mouseY, dotsCenterX - hit / 2.0F, cardCenterY - hit / 2.0F, hit, hit)) {
                return feature;
            }
        }
        return null;
    }

    private static FeatureCategory sidebarCategoryAt(float panelX, float panelY, float scale, int mouseX, int mouseY) {
        float sidebarWidth = SIDEBAR_WIDTH * scale;
        if (mouseX < panelX || mouseX > panelX + sidebarWidth) {
            return null;
        }
        float topPadding = sidebarTopPadding(PANEL_HEIGHT * scale, scale);
        for (SidebarRow row : sidebarRows(panelY, scale, topPadding)) {
            if (row.category() == null) {
                continue;
            }
            if (mouseY >= row.y() && mouseY <= row.y() + row.height()) {
                return row.category();
            }
        }
        return null;
    }

    private static float sidebarTopPadding(float panelHeight, float scale) {
        float listHeight = 0.0F;
        for (int g = 0; g < SECTION_LABELS.length; g++) {
            if (g > 0) {
                listHeight += SIDEBAR_GROUP_GAP * scale;
            }
            listHeight += SIDEBAR_HEADER_HEIGHT * scale + SIDEBAR_HEADER_GAP * scale;
            FeatureCategory[] cats = SECTION_CATEGORIES[g];
            listHeight += cats.length * SIDEBAR_ITEM_HEIGHT * scale + (cats.length - 1) * SIDEBAR_ITEM_GAP * scale;
        }
        return (panelHeight - listHeight) / 2.0F;
    }

    // ── animation helpers ────────────────────────────────────────────────────

    /**
     * Frame-rate-independent exponential smoothing toward {@code target}, one running
     * value per {@code key} (identity). Snaps once it's within a hair to keep the map
     * from lingering on tiny residuals.
     */
    private static float anim(Object key, float target, float speed) {
        float current = ANIM.getOrDefault(key, target);
        float t = 1.0F - (float) Math.exp(-frameDelta * speed);
        current += (target - current) * t;
        if (Math.abs(target - current) < 0.0015F) {
            current = target;
        }
        ANIM.put(key, current);
        return current;
    }

    private static final Map<Object, Object> KEY_CACHE = new IdentityHashMap<>();

    private static Object hoverKey(Object base) {
        return subKey(base, "hover");
    }

    private static Object pillAnimKey(Object base) {
        return subKey(base, "pill");
    }

    private static Object flyoutAnimKey(Object base) {
        return subKey(base, "flyout");
    }

    private static Object dotsAnimKey(Object base) {
        return subKey(base, "dots");
    }

    /** A stable secondary identity key so one setting can drive several independent
     * animated values (its toggle vs its pill-open vs its flyout) without colliding. */
    private static Object subKey(Object base, String tag) {
        Object[] arr = (Object[]) KEY_CACHE.get(base);
        if (arr == null) {
            arr = new Object[]{new Object(), new Object(), new Object(), new Object()};
            KEY_CACHE.put(base, arr);
        }
        return switch (tag) {
            case "hover" -> arr[0];
            case "pill" -> arr[1];
            case "dots" -> arr[3];
            default -> arr[2];
        };
    }

    private static int mixColor(int a, int b, float t) {
        t = MathUtil.clamp01(t);
        int ar = ColorUtil.alpha(a);
        int rr = ColorUtil.red(a);
        int gr = ColorUtil.green(a);
        int br = ColorUtil.blue(a);
        int ab = ColorUtil.alpha(b);
        int rb = ColorUtil.red(b);
        int gb = ColorUtil.green(b);
        int bb = ColorUtil.blue(b);
        int alpha = Math.round(ar + (ab - ar) * t);
        int red = Math.round(rr + (rb - rr) * t);
        int green = Math.round(gr + (gb - gr) * t);
        int blue = Math.round(br + (bb - br) * t);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static boolean caretVisible() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    // ── misc ─────────────────────────────────────────────────────────────────

    private static void open(Minecraft minecraft) {
        boolean grabbedMouseBeforeOpen = minecraft.mouseHandler.isMouseGrabbed();
        open = true;
        lastFrameNanos = 0L;
        KeyMapping.releaseAll();
        if (grabbedMouseBeforeOpen) {
            minecraft.mouseHandler.releaseMouse();
        }
    }

    private static boolean shouldReturnMouseToGame(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null;
    }

    private static int mouseX(Minecraft minecraft) {
        return (int) Math.round(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()));
    }

    private static int mouseY(Minecraft minecraft) {
        return (int) Math.round(minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
    }
}
