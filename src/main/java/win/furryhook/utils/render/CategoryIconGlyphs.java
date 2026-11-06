package win.furryhook.utils.render;

import win.furryhook.feature.FeatureCategory;

/**
 * Combat/Movement category icons ported from divine's CategoryBarSection, which renders those
 * two categories as glyphs from its icon font ("D"/"E") instead of raster textures like the
 * other categories. furryhook already ships that same font as {@code UiFonts.divineNotificationIcons()}.
 */
public final class CategoryIconGlyphs {
    private static final String COMBAT_GLYPH = "D";
    private static final String MOVEMENT_GLYPH = "E";

    private CategoryIconGlyphs() {
    }

    public static boolean isGlyph(FeatureCategory category) {
        return category == FeatureCategory.COMBAT || category == FeatureCategory.MOVEMENT;
    }

    public static String glyph(FeatureCategory category) {
        return category == FeatureCategory.COMBAT ? COMBAT_GLYPH : MOVEMENT_GLYPH;
    }
}
