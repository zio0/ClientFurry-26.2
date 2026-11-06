package win.furryhook.pve.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningToolProfileTest {
    @Test
    void recognizesFunTimeBulldozerLevels() {
        assertEquals(
                MiningToolProfile.BULLDOZER_I,
                MiningToolProfile.detect("Особые чары\nБульдозер I")
        );
        assertEquals(
                MiningToolProfile.BULLDOZER_I,
                MiningToolProfile.detect("бульдозер: 1")
        );
        assertEquals(
                MiningToolProfile.BULLDOZER_II,
                MiningToolProfile.detect("§6БУЛЬДОЗЕР II")
        );
        assertEquals(
                MiningToolProfile.BULLDOZER_II,
                MiningToolProfile.detect("Бульдозер-2")
        );
    }

    @Test
    void standardToolHasSingleBlockFootprint() {
        assertEquals(MiningToolProfile.STANDARD,
                MiningToolProfile.detect("Эффективность V"));
        assertEquals(1, MiningToolProfile.STANDARD.maximumBlocksPerBreak());
        assertEquals(9, MiningToolProfile.BULLDOZER_I.maximumBlocksPerBreak());
        assertEquals(27, MiningToolProfile.BULLDOZER_II.maximumBlocksPerBreak());
    }
}
