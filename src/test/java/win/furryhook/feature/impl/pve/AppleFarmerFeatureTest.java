package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppleFarmerFeatureTest {
    @Test
    void followsHarvestAndAppleStoragePhases() {
        assertEquals(
                AppleFarmerFeature.Phase.APPROACH_FARM,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.FIND_FARM,
                        signals(false, false, false, false, false, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.PLANT,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.APPROACH_FARM,
                        signals(true, false, false, false, false, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.GROW,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.PLANT,
                        signals(true, false, true, false, false, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.BREAK_LEAVES,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.GROW,
                        signals(true, false, false, false, false, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.BREAK_LOGS,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.BREAK_LEAVES,
                        signals(true, false, true, false, false, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.DROP_JUNK,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.BREAK_LOGS,
                        signals(true, false, true, false, false, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.DEPOSIT_APPLES_FIND,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.DROP_JUNK,
                        signals(true, false, false, false, true, false, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.DEPOSIT_APPLES_MOVE,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.DEPOSIT_APPLES_FIND,
                        signals(true, false, false, false, true, true, false,
                                false, false, false, false, false, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.DEPOSIT_APPLES_OPEN,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.DEPOSIT_APPLES_MOVE,
                        signals(true, false, false, false, true, true, true,
                                false, false, false, false, false, false, false)
                )
        );
    }

    @Test
    void prioritizesBoneCraftingAndRepairWithoutLoopingOnMissingStorage() {
        assertEquals(
                AppleFarmerFeature.Phase.CRAFT_BONE_MEAL,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.DROP_JUNK,
                        signals(true, false, false, false, false, false, false,
                                false, true, true, true, true, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.REPAIR,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.CRAFT_BONE_MEAL,
                        signals(true, false, false, false, false, false, false,
                                false, false, false, true, true, false, false)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.PLANT,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.BONES_FIND,
                        signals(true, false, false, false, false, false, false,
                                false, false, false, false, false, false, true)
                )
        );
        assertEquals(
                AppleFarmerFeature.Phase.RETURN_HOME,
                AppleFarmerFeature.nextPhase(
                        AppleFarmerFeature.Phase.APPROACH_FARM,
                        signals(false, false, false, false, false, false, false,
                                false, false, false, false, false, false, true)
                )
        );
    }

    @Test
    void recognizesLocalizedStorageSigns() {
        assertTrue(AppleFarmerFeature.matchesStorageLabel(
                List.of("§aAPPLES"),
                AppleFarmerFeature.StorageKind.APPLES
        ));
        assertTrue(AppleFarmerFeature.matchesStorageLabel(
                List.of("[ Кости ]"),
                AppleFarmerFeature.StorageKind.BONES
        ));
        assertFalse(AppleFarmerFeature.matchesStorageLabel(
                List.of("tools"),
                AppleFarmerFeature.StorageKind.BONES
        ));
    }

    private static AppleFarmerFeature.FarmerSignals signals(
            boolean atFarm,
            boolean workRemaining,
            boolean hasResource,
            boolean hasJunk,
            boolean shouldDepositApples,
            boolean destinationKnown,
            boolean atDestination,
            boolean inventoryWorkRemaining,
            boolean shouldFetchBones,
            boolean hasBones,
            boolean needsRepair,
            boolean hasBottles,
            boolean canBuyBottles,
            boolean timedOut
    ) {
        return new AppleFarmerFeature.FarmerSignals(
                atFarm,
                workRemaining,
                hasResource,
                hasJunk,
                shouldDepositApples,
                destinationKnown,
                atDestination,
                inventoryWorkRemaining,
                shouldFetchBones,
                hasBones,
                needsRepair,
                hasBottles,
                canBuyBottles,
                timedOut
        );
    }
}
