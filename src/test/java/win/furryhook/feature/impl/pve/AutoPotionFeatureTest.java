package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutoPotionFeatureTest {
    @Test
    void choosesFirstSelectedMissingAvailablePotion() {
        int selected = AutoPotionFeature.selectPotionIndex(
                new boolean[]{true, true, true},
                new boolean[]{true, false, false},
                new int[]{36, -1, 15}
        );

        assertEquals(2, selected);
    }

    @Test
    void returnsNoneWhenEffectsAreActiveOrPotionsMissing() {
        int selected = AutoPotionFeature.selectPotionIndex(
                new boolean[]{true, true, false},
                new boolean[]{true, false, false},
                new int[]{36, -1, 15}
        );

        assertEquals(-1, selected);
    }

    @Test
    void rejectsMismatchedStateArrays() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoPotionFeature.selectPotionIndex(
                        new boolean[]{true},
                        new boolean[]{false, false},
                        new int[]{36}
                )
        );
    }
}
