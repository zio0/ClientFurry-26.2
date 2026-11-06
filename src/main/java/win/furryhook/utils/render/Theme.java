package win.furryhook.utils.render;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.ColorUtil;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Theme {
    public static final int CUSTOM_ACCENT_INDEX = Colors.ACCENTS.length - 1;

    private static int accentIndex;
    private static AccentMode accentMode = AccentMode.STATIC;

    /**
     * How the accent behaves. Everything in the client reads {@link #getAccent()} every
     * frame, so switching this animates the whole GUI plus every feature left on the
     * "Sync" color mode; {@link #baseAccent()} stays the flat picked swatch.
     */
    public enum AccentMode {
        STATIC("Static"),
        RAINBOW("Rainbow"),
        ASTOLFO("Astolfo");

        private final String label;

        AccentMode(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public static AccentMode byLabel(String label) {
            if (label != null) {
                for (AccentMode mode : values()) {
                    if (mode.label.equalsIgnoreCase(label) || mode.name().equalsIgnoreCase(label)) {
                        return mode;
                    }
                }
            }
            return STATIC;
        }
    }

    public static int getAccent() {
        return switch (accentMode) {
            case STATIC -> Colors.ACCENTS[accentIndex];
            case RAINBOW -> ColorUtil.rainbow(1.0F, 0);
            case ASTOLFO -> ColorUtil.astolfo(1.0F, 0);
        };
    }

    /** The picked accent, ignoring any animated mode. */
    public static int baseAccent() {
        return Colors.ACCENTS[accentIndex];
    }

    public static AccentMode accentMode() {
        return accentMode;
    }

    public static void setAccentMode(AccentMode mode) {
        accentMode = mode == null ? AccentMode.STATIC : mode;
    }

    public static boolean isAccentAnimated() {
        return accentMode != AccentMode.STATIC;
    }

    public static int accent(int index) {
        return Colors.ACCENTS[MathUtil.clamp(index, 0, Colors.ACCENTS.length - 1)];
    }

    public static int accentCount() {
        return Colors.ACCENTS.length;
    }

    public static void setAccentIndex(int index) {
        accentIndex = MathUtil.clamp(index, 0, Colors.ACCENTS.length - 1);
    }

    public static int accentIndex() {
        return accentIndex;
    }

    public static void saveAccentPreset(int index, int color) {
        Colors.ACCENTS[MathUtil.clamp(index, 0, Colors.ACCENTS.length - 1)] = ColorUtil.withAlpha(color, 255);
        accentIndex = MathUtil.clamp(index, 0, Colors.ACCENTS.length - 1);
    }

    /**
     * Fascia: a flat, matte "instrument panel" palette. No glass, no glow — structure
     * comes from hairline dividers, not shadows, so PANEL_SHADOW/POPUP_SHADOW/etc. are
     * kept (call sites still reference them) but zeroed to alpha 0.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Colors {
        public static final int PANEL = ColorUtil.rgb(19, 20, 22);
        public static final int BACKGROUND_PRIMARY_50 = ColorUtil.rgba(19, 20, 22, 128);
        public static final int PANEL_BORDER = ColorUtil.rgba(255, 255, 255, 16);
        public static final int PANEL_SHADOW = ColorUtil.rgba(0, 0, 0, 0);
        public static final int PANEL_SHADOW_STRONG = ColorUtil.rgba(0, 0, 0, 0);
        public static final int POPUP_SHADOW = ColorUtil.rgba(0, 0, 0, 0);
        public static final int OVERLAY = ColorUtil.rgb(15, 16, 18);

        public static final int CARD = ColorUtil.rgb(24, 25, 27);
        public static final int CARD_HOVER = ColorUtil.rgb(30, 31, 34);
        public static final int MODULE_CARD = ColorUtil.rgb(21, 22, 24);
        public static final int MODULE_CARD_HOVER = ColorUtil.rgb(27, 28, 31);
        public static final int BACKGROUND_SURFACE_S = ColorUtil.rgb(23, 24, 26);
        public static final int BACKGROUND_SURFACE_M = ColorUtil.rgb(29, 30, 33);
        public static final int CONTROL = ColorUtil.rgb(33, 34, 37);
        public static final int CONTROL_ALT = ColorUtil.rgb(33, 34, 37);
        public static final int CONTROL_STRONG = ColorUtil.rgb(35, 36, 39);
        public static final int CONTROL_HOVER = ColorUtil.rgb(40, 41, 45);
        public static final int CONTROL_IDLE = ColorUtil.rgb(32, 33, 36);
        public static final int OUTLINES_SMALL = ColorUtil.rgba(255, 255, 255, 14);
        public static final int OUTLINES_MEDIUM = ColorUtil.rgba(255, 255, 255, 20);
        public static final int OUTLINES_LARGE = ColorUtil.rgba(255, 255, 255, 28);
        public static final int SYSTEM_INFORMATION = ColorUtil.rgb(90, 150, 214);
        public static final int SYSTEM_RED = ColorUtil.rgb(196, 90, 80);
        public static final int CONTROL_SHADOW = ColorUtil.rgba(0, 0, 0, 0);
        public static final int TEXT_TITLE = ColorUtil.rgb(237, 238, 235);
        public static final int TEXT_TEXT = ColorUtil.rgb(214, 216, 212);
        public static final int TEXT = ColorUtil.rgb(224, 225, 221);
        public static final int PRIMARY = ColorUtil.rgb(232, 233, 229);
        public static final int PRIMARY_BRIGHT = ColorUtil.rgb(240, 241, 237);
        public static final int SECONDARY = ColorUtil.rgb(150, 153, 148);
        public static final int SECONDARY_DARK = ColorUtil.rgb(126, 129, 124);
        public static final int SECTION = ColorUtil.rgb(120, 123, 118);
        public static final int DIVIDER = ColorUtil.rgba(255, 255, 255, 20);
        public static final int DIVIDER_SUBTLE = ColorUtil.rgba(255, 255, 255, 14);
        public static final int DIVIDER_HEADER = ColorUtil.rgba(255, 255, 255, 16);
        public static final int SEPARATOR = ColorUtil.rgba(255, 255, 255, 20);
        public static final int SURFACE_ACTIVE = ColorUtil.rgba(255, 255, 255, 26);
        public static final int SURFACE_HOVER = ColorUtil.rgba(255, 255, 255, 12);
        public static final int FOCUS = ColorUtil.rgba(255, 255, 255, 128);
        public static final int ICON = ColorUtil.rgb(150, 153, 148);
        public static final int ICON_MUTED = ColorUtil.rgb(190, 192, 187);
        public static final int ICON_GHOST = ColorUtil.rgb(90, 92, 88);
        public static final int TEXT_GHOST = ICON_GHOST;
        public static final int TRAFFIC_CLOSE = ColorUtil.rgb(196, 90, 80);
        public static final int TRAFFIC_MINIMIZE = ColorUtil.rgb(214, 173, 90);
        public static final int TRAFFIC_MAXIMIZE = ColorUtil.rgb(120, 168, 108);
        public static final int RISK = TRAFFIC_MINIMIZE;
        public static final int EXTRA_RISK = TRAFFIC_CLOSE;
        public static final int DOCK = ColorUtil.rgba(21, 22, 24, 218);
        public static final int DOCK_BORDER = ColorUtil.rgba(255, 255, 255, 30);
        public static final int DOCK_SHADOW = ColorUtil.rgba(0, 0, 0, 0);
        public static final int[] ACCENTS = {
                ColorUtil.rgb(0, 162, 255), ColorUtil.rgb(196, 140, 64), ColorUtil.rgb(196, 94, 66),
                ColorUtil.rgb(180, 86, 86), ColorUtil.rgb(168, 104, 150), ColorUtil.rgb(96, 132, 196),
                ColorUtil.rgb(90, 150, 140), ColorUtil.rgb(120, 150, 90), ColorUtil.rgb(150, 150, 90),
                ColorUtil.rgb(140, 140, 140), ColorUtil.rgb(227, 122, 52)
        };
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Sizes {
        public static final int HEADER_HEIGHT = 56;
        public static final int DEFAULT_PANEL_RADIUS_INDEX = 1;
        public static final int[] PANEL_RADII = {0, 12, 20};
        public static final int MODULE_CARD_RADIUS = 8;
        public static final int MODULE_CARD_HEADER_HEIGHT = 64;
        public static final int MODULE_CARD_ROW_HEIGHT = 40;
        public static final int MODULE_CARD_PADDING = 16;
        public static final int MODULE_CARD_SWITCH_WIDTH = 36;
        public static final int MODULE_CARD_SWITCH_HEIGHT = 16;
        public static final int MODULE_CARD_ACTION_ICON = 12;
        public static final int SLIDER_WIDTH = 128;
        public static final int SLIDER_HEIGHT = 16;
        public static final int SLIDER_PADDING = 4;
        public static final int SLIDER_TRACK_HEIGHT = 2;
        public static final int SLIDER_KNOB_SIZE = 14;
        public static final int SLIDER_CAP_WIDTH = 3;
        public static final int SLIDER_TICK_COUNT = 4;
        public static final float SLIDER_SHADOW_BLUR = 7.5F;
        public static final int MODULE_CARD_SLIDER_WIDTH = SLIDER_WIDTH;
        public static final int MODULE_CARD_SLIDER_HEIGHT = SLIDER_HEIGHT;
        public static final int COLOR_PREVIEW_WIDTH = 24;
        public static final int COLOR_PREVIEW_HEIGHT = 16;
        public static final int COLOR_PREVIEW_PADDING = 2;
        public static final int COLOR_PREVIEW_RADIUS = 0;
        public static final int COLOR_BLOCK_RADIUS = 0;
        public static final int COLOR_PICKER_WIDTH = 148;
        public static final int COLOR_PICKER_PADDING = 8;
        public static final int COLOR_PICKER_RADIUS = 8;
        public static final int COLOR_PICKER_CONTENT_WIDTH = 132;
        public static final int COLOR_PICKER_OFFSET_Y = 4;
        public static final int COLOR_PICKER_GAP = 6;
        public static final int COLOR_PICKER_SV_HEIGHT = 82;
        public static final int COLOR_PICKER_SV_RADIUS = 4;
        public static final int COLOR_PICKER_SV_THUMB_RADIUS = 5;
        public static final int COLOR_PICKER_SLIDER_ROW_HEIGHT = 12;
        public static final int COLOR_PICKER_SLIDER_TRACK_HEIGHT = 6;
        public static final int COLOR_PICKER_SLIDER_THUMB_RADIUS = 5;
        public static final int DROPDOWN_VALUE_WIDTH = 112;
        public static final int DROPDOWN_VALUE_HEIGHT = 24;
        public static final int DROPDOWN_GAP = 4;
        public static final int DROPDOWN_ICON_SIZE = 24;
        public static final int DROPDOWN_ICON_PADDING = 4;
        public static final int DROPDOWN_POPUP_WIDTH = 144;
        public static final int DROPDOWN_POPUP_RADIUS = 0;
        public static final int DROPDOWN_POPUP_PADDING = 0;
        public static final int DROPDOWN_POPUP_GAP = 0;
        public static final int DROPDOWN_POPUP_ITEM_HEIGHT = 30;
        public static final int DROPDOWN_POPUP_ITEM_RADIUS = 0;
        public static final int DROPDOWN_POPUP_TEXT_WIDTH = 108;
        public static final int DROPDOWN_POPUP_OFFSET_Y = 2;
        public static final int BIND_POPUP_WIDTH = 240;
        public static final int BIND_POPUP_PADDING = 10;
        public static final int BIND_POPUP_GAP = 3;
        public static final int INPUT_BIND_WIDTH = 44;
        public static final int INPUT_BIND_HEIGHT = 20;
        public static final int INPUT_BIND_RADIUS = 0;
        public static final int INPUT_BIND_PADDING_X = 8;
        public static final int INPUT_BIND_TEXT_SIZE = 10;
        public static final int INPUT_WIDTH = 128;
        public static final int INPUT_HEIGHT = 24;
        public static final int INPUT_RADIUS = 0;
        public static final int INPUT_PADDING_X = 8;
        public static final int INPUT_PADDING_Y = 4;
        public static final int INPUT_TEXT_WIDTH = 112;
        public static final int INPUT_TEXT_SIZE = 10;
        public static final int HEADER_CONTROLS_X = 16;
        public static final int HEADER_CONTROLS_Y = 12;
        public static final int HEADER_CONTROLS_HEIGHT = 32;
        public static final int HEADER_CONTROLS_GAP = 8;
        public static final int HEADER_CONTROL_BUTTON_SIZE = 24;
        public static final int HEADER_CONTROL_BUTTON_PADDING = 4;
        public static final int HEADER_NAV_GROUP_WIDTH = 48;
        public static final int HEADER_ICON_SIZE = 16;
        public static final int HEADER_DIVIDER_HEIGHT = 12;
    }
}
