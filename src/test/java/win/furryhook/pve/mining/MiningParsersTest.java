package win.furryhook.pve.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningParsersTest {
    @Test
    void normalizesAndDeduplicatesIdentifiers() {
        assertEquals(
                Set.of("minecraft:diamond_ore", "mod:rich_ore"),
                MiningParsers.identifiers(
                        "diamond_ore, minecraft:diamond_ore; MOD:Rich_Ore invalid$id"
                )
        );
    }

    @Test
    void parsesRouteAndSkipsBadPoints() {
        assertEquals(
                List.of(
                        new MiningParsers.GridPoint(1, 2, 3),
                        new MiningParsers.GridPoint(-4, 5, 6)
                ),
                MiningParsers.route("1,2,3; nope; -4, 5, 6")
        );
    }

    @Test
    void regionIsInclusiveAndRejectsMalformedValues() {
        MiningParsers.Region region = MiningParsers.region(
                "10,20,30 -> 0,10,20"
        ).orElseThrow();

        assertTrue(region.contains(new BlockPos(0, 10, 20)));
        assertTrue(region.contains(new BlockPos(10, 20, 30)));
        assertFalse(region.contains(new BlockPos(11, 20, 30)));
        assertTrue(MiningParsers.region("1,2:3,4").isEmpty());
    }
}
