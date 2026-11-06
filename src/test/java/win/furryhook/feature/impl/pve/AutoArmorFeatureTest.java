package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoArmorFeatureTest {
    @Test
    void scoresRecoveredArmorComponents() {
        assertEquals(
                9.5D,
                AutoArmorFeature.score(3.0D, 2.0D, 4, 3, 1),
                1.0E-9D
        );
    }

    @Test
    void selectsHighestStrictUpgrade() {
        OptionalInt selected = AutoArmorFeature.selectBestUpgrade(
                7.0D,
                List.of(
                        new AutoArmorFeature.ScoredSlot(12, 7.0D),
                        new AutoArmorFeature.ScoredSlot(20, 8.2D),
                        new AutoArmorFeature.ScoredSlot(30, 8.1D)
                )
        );

        assertEquals(20, selected.orElseThrow());
    }

    @Test
    void rejectsEqualOrWorseArmor() {
        OptionalInt selected = AutoArmorFeature.selectBestUpgrade(
                8.0D,
                List.of(
                        new AutoArmorFeature.ScoredSlot(12, 7.9D),
                        new AutoArmorFeature.ScoredSlot(20, 8.0D)
                )
        );

        assertTrue(selected.isEmpty());
    }

    @Test
    void emptyEquipmentAcceptsZeroScoreArmor() {
        OptionalInt selected = AutoArmorFeature.selectBestUpgrade(
                Double.NEGATIVE_INFINITY,
                List.of(new AutoArmorFeature.ScoredSlot(14, 0.0D))
        );

        assertEquals(14, selected.orElseThrow());
    }
}
