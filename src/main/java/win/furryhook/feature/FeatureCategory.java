package win.furryhook.feature;

import lombok.Getter;

@Getter
public enum FeatureCategory {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    VISUAL("Visuals"),
    PLAYER("Player"),
    MISC("Misc"),
    COSMETICS("Cosmetics"),
    SCRIPTS("Scripts");

    private final String displayName;

    FeatureCategory(String displayName) {
        this.displayName = displayName;
    }
}
