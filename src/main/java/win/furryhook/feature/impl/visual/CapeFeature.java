package win.furryhook.feature.impl.visual;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.ModeSetting;
import java.util.Locale;

public final class CapeFeature extends Feature {

    public enum CapeStyle {
        APPICON("appicon.png"),
        ASCII("ascii.png"),
        BARCODE("barcode.png"),
        GLASS("glass.png"),
        MINIMALPATTERN("minimalpatern.png"),
        MOTTO("motto.png"),
        VANDAL("vandal.png"),
        PMCBOYKISSER("pmcboykisser.png");

        private final String fileName;

        CapeStyle(String fileName) {
            this.fileName = fileName;
        }

        public String getFileName() {
            return this.fileName;
        }
    }

    public final ModeSetting mode = register(new ModeSetting(
            "Style",
            "PMCBoykisser",
            "AppIcon", "ASCII", "Barcode", "Glass", "MinimalPattern", "Motto", "Vandal", "PMCBoykisser"
    ));

    public CapeFeature() {
        super("Capes", "Custom cosmetic capes visible on you", FeatureCategory.VISUAL, BindSetting.UNBOUND);
        // Hidden from the module grid but still registered so cape rendering keeps working; off by default.
        setVisible(false);
    }

    @Override
    public boolean isMenuHidden() {
        return true;
    }

    public CapeStyle currentStyle() {
        try {
            return CapeStyle.valueOf(mode.getValue().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CapeStyle.PMCBOYKISSER;
        }
    }
}
