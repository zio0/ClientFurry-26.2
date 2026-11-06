package win.furryhook.feature.impl.visual;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;

public final class ItemPhysicsFeature extends Feature {
    public ItemPhysicsFeature() {
        super("ItemPhysics", "Dropped items lie on the ground", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }
}
