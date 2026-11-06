package win.furryhook.feature.impl.misc;

import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;

/**
 * Reads a player's real health from the below-name scoreboard objective so that
 * HUD/ESP elements show the true value when a server displays a fake one.
 */
public final class ScoreboardHealthFeature extends Feature {
    public ScoreboardHealthFeature() {
        super("ScoreboardHealth", "Fixes a target's health when the server shows a fake value",
                FeatureCategory.MISC, BindSetting.UNBOUND);
    }

    public static boolean isActive() {
        return FeatureManager.INSTANCE.getEnabled(ScoreboardHealthFeature.class) != null;
    }
}
