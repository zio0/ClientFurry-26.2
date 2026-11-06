package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreeperFarmFeatureTest {
    @Test
    void followsRecoveredPhaseOrder() {
        assertEquals(
                CreeperFarmFeature.Phase.LOADING_CHUNKS,
                CreeperFarmFeature.nextPhase(
                        CreeperFarmFeature.Phase.APPROACH,
                        new CreeperFarmFeature.FarmSignals(true, false, false, false, false)
                )
        );
        assertEquals(
                CreeperFarmFeature.Phase.LOOTING,
                CreeperFarmFeature.nextPhase(
                        CreeperFarmFeature.Phase.LOADING_CHUNKS,
                        new CreeperFarmFeature.FarmSignals(true, true, false, false, false)
                )
        );
        assertEquals(
                CreeperFarmFeature.Phase.UNLOADING,
                CreeperFarmFeature.nextPhase(
                        CreeperFarmFeature.Phase.LOOTING,
                        new CreeperFarmFeature.FarmSignals(true, true, true, false, false)
                )
        );
        assertEquals(
                CreeperFarmFeature.Phase.LOOTING,
                CreeperFarmFeature.nextPhase(
                        CreeperFarmFeature.Phase.UNLOADING,
                        new CreeperFarmFeature.FarmSignals(true, true, true, true, false)
                )
        );
        assertEquals(
                CreeperFarmFeature.Phase.APPROACH,
                CreeperFarmFeature.nextPhase(
                        CreeperFarmFeature.Phase.UNLOADING,
                        new CreeperFarmFeature.FarmSignals(false, true, true, false, true)
                )
        );
    }

    @Test
    void parsesPositiveLocalizedMoneyMessages() {
        assertEquals(
                1_250.0D,
                CreeperFarmFeature.parseMoneyDelta("Sold 32 gunpowder: +$1,250")
                        .orElseThrow(),
                0.001D
        );
        assertEquals(
                1_250.5D,
                CreeperFarmFeature.parseMoneyDelta("§aПолучено: +1 250,50 ₽")
                        .orElseThrow(),
                0.001D
        );
        assertEquals(
                2_000_000.0D,
                CreeperFarmFeature.parseMoneyDelta("Earned +$2m")
                        .orElseThrow(),
                0.001D
        );
        assertTrue(CreeperFarmFeature.parseMoneyDelta("Balance: $5,000").isEmpty());
        assertTrue(CreeperFarmFeature.parseMoneyDelta("Picked up 16 gunpowder").isEmpty());
    }

    @Test
    void recognizesGunpowderStorageLabels() {
        assertTrue(CreeperFarmFeature.matchesGunpowderLabel(List.of("[Gunpowder]")));
        assertTrue(CreeperFarmFeature.matchesGunpowderLabel(List.of("§6Порох")));
        assertFalse(CreeperFarmFeature.matchesGunpowderLabel(List.of("food")));
    }
}
