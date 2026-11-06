package win.furryhook.pve.mining;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningTargetSelectorTest {
    @Test
    void orePriorityWinsBeforeDistance() {
        var stone = candidate("stone", false, true, 1.0D, 1.5F);
        var ore = candidate("ore", true, true, 12.0D, 3.0F);

        var selected = MiningTargetSelector.select(
                List.of(stone, ore),
                MiningTargetSelector.DiggingMode.ORE_PRIORITY,
                false
        );

        assertEquals("ore", selected.orElseThrow().value());
    }

    @Test
    void onlyOreRejectsUnsafeAndHiddenTargets() {
        var unsafeOre = new MiningTargetSelector.Candidate<>(
                "unsafe", false, true, true, true, 0, 1.0D, 1.0F
        );
        var hiddenOre = candidate("hidden", true, false, 2.0D, 1.0F);

        assertTrue(MiningTargetSelector.select(
                List.of(unsafeOre, hiddenOre),
                MiningTargetSelector.DiggingMode.ONLY_ORE,
                false
        ).isEmpty());
    }

    @Test
    void neighborMustBeSaferAndEasier() {
        var primary = candidate("ore", true, true, 2.0D, 4.0F);
        var hard = candidate("hard", false, true, 1.0D, 5.0F);
        var easy = candidate("easy", false, true, 3.0D, 1.0F);

        assertEquals("easy", MiningTargetSelector.easierNeighbor(
                primary,
                List.of(hard, easy),
                false
        ).orElseThrow().value());
    }

    private static MiningTargetSelector.Candidate<String> candidate(
            String value,
            boolean ore,
            boolean visible,
            double distance,
            float hardness
    ) {
        return new MiningTargetSelector.Candidate<>(
                value, true, ore, visible, true, 0, distance, hardness
        );
    }
}
