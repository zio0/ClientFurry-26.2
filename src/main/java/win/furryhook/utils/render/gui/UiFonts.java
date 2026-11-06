package win.furryhook.utils.render.gui;

public final class UiFonts {
    private UiFonts() {
    }

    public static MsdfFontFamily divineProductSans() {
        return Holder.DIVINE_PRODUCT_SANS;
    }

    public static MsdfFont divineProductSans(int weight) {
        return Holder.DIVINE_PRODUCT_SANS.resolve(weight);
    }

    public static MsdfFont divineMaterialIcons() {
        return Holder.DIVINE_MATERIAL_ICONS.regular();
    }

    public static MsdfFont divineNotificationIcons() {
        return Holder.DIVINE_NOTIFICATION_ICONS.regular();
    }

    public static MsdfFont pawIcon() {
        return Holder.PAW_ICON.regular();
    }

    public static MsdfFontFamily googleSansFlex() {
        return Holder.GOOGLE_SANS_FLEX;
    }

    public static MsdfFont googleSansFlex(int weight) {
        return Holder.GOOGLE_SANS_FLEX.resolve(weight);
    }

    public static MsdfFontFamily googleSans() {
        return Holder.GOOGLE_SANS;
    }

    public static MsdfFont googleSans(int weight) {
        return Holder.GOOGLE_SANS.resolve(weight);
    }

    public static MsdfFontFamily sfPro() {
        return Holder.SF_PRO;
    }

    public static MsdfFont sfPro(int weight) {
        return Holder.SF_PRO.resolve(weight);
    }

    public static MsdfFont sfProDisplay() {
        return Holder.SF_PRO.regular();
    }

    public static MsdfFont getFontForText(String text, int weight) {
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= '\u0400' && c <= '\u04FF') {
                    return googleSans(weight);
                }
            }
        }
        return googleSansFlex(weight);
    }

    private static final class Holder {
        private static final MsdfFontFamily GOOGLE_SANS_FLEX = MsdfFontFamily.builder()
                .variant(400, "furryhook:fonts/google_sans_flex_regular.json")
                .variant(500, "furryhook:fonts/google_sans_flex_medium.json")
                .variant(600, "furryhook:fonts/google_sans_flex_semibold.json")
                .variant(700, "furryhook:fonts/google_sans_flex_bold.json")
                .build();

        private static final MsdfFontFamily GOOGLE_SANS = MsdfFontFamily.builder()
                .variant(400, "furryhook:fonts/google_sans_regular.json")
                .variant(500, "furryhook:fonts/google_sans_medium.json")
                .variant(600, "furryhook:fonts/google_sans_semibold.json")
                .variant(700, "furryhook:fonts/google_sans_bold.json")
                .build();

        private static final MsdfFontFamily SF_PRO = MsdfFontFamily.builder()
                .variant(400, "furryhook:fonts/sf_pro_regular.json")
                .variant(500, "furryhook:fonts/sf_pro_medium.json")
                .variant(600, "furryhook:fonts/sf_pro_semibold.json")
                .variant(700, "furryhook:fonts/sf_pro_bold.json")
                .build();

        // Ported from divine's HUD (GFonts.OPAL_MEDIUM/OPAL_BOLD, GFonts.OPAL_ICONS, GFonts.NOTIFICATION_ICONS).
        private static final MsdfFontFamily DIVINE_PRODUCT_SANS = MsdfFontFamily.builder()
                .variant(500, "furryhook:fonts/divine_product_sans_medium.json")
                .variant(700, "furryhook:fonts/divine_product_sans_bold.json")
                .build();

        private static final MsdfFontFamily DIVINE_MATERIAL_ICONS = MsdfFontFamily.builder()
                .variant(400, "furryhook:fonts/divine_material_icons.json")
                .build();

        private static final MsdfFontFamily DIVINE_NOTIFICATION_ICONS = MsdfFontFamily.builder()
                .variant(400, "furryhook:fonts/divine_notification_icons.json")
                .build();

        private static final MsdfFontFamily PAW_ICON = MsdfFontFamily.builder()
                .variant(400, "furryhook:fonts/paw_icon.json")
                .build();
    }
}
