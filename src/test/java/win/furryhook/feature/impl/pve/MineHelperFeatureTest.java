package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MineHelperFeatureTest {
    @Test
    void dropIntervalUsesOneSecondToOneMinuteRange() {
        MineHelperFeature feature = new MineHelperFeature();

        assertEquals(1.0D, feature.dropInterval.getDefaultValue());
        assertEquals(1.0D, feature.dropInterval.getMin());
        assertEquals(60.0D, feature.dropInterval.getMax());
    }
}
