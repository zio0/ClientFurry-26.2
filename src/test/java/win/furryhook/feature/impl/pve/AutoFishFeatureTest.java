package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoFishFeatureTest {
    @Test
    void durabilityOutranksUnbreaking() {
        assertTrue(AutoFishFeature.compareRodQuality(51, 0, 50, 3) > 0);
    }

    @Test
    void unbreakingBreaksEqualDurabilityTie() {
        assertTrue(AutoFishFeature.compareRodQuality(50, 3, 50, 2) > 0);
    }

    @Test
    void saveThresholdRequiresMoreThanTenUses() {
        assertFalse(AutoFishFeature.isUsableRodDurability(10));
        assertTrue(AutoFishFeature.isUsableRodDurability(11));
    }
}
