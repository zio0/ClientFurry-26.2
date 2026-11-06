package win.furryhook.feature.impl.misc;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.TextSetting;

public final class NameProtectFeature extends Feature {
    public final TextSetting name = register(new TextSetting("Name", "BladeUser", 32));

    public NameProtectFeature() {
        super("NameProtect", "Protects your nickname in client UI", FeatureCategory.MISC, BindSetting.UNBOUND);
    }
}
