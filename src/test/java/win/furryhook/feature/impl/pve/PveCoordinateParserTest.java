package win.furryhook.feature.impl.pve;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PveCoordinateParserTest {
    @Test
    void parsesCommonCoordinateForms() {
        assertEquals(
                new BlockPos(12, 64, -30),
                PveCoordinateParser.parse("12, 64, -30").orElseThrow()
        );
        assertEquals(
                new BlockPos(-4, 70, 9),
                PveCoordinateParser.parse("[-4; 70; 9]").orElseThrow()
        );
    }

    @Test
    void treatsAutomaticAndMalformedValuesAsUnconfigured() {
        assertTrue(PveCoordinateParser.parse("auto").isEmpty());
        assertTrue(PveCoordinateParser.parse("").isEmpty());
        assertTrue(PveCoordinateParser.parse("1,2").isEmpty());
        assertTrue(PveCoordinateParser.parse("one,2,3").isEmpty());
    }
}
