package win.furryhook.pve.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigationOptionsTest {
    @Test
    void autoMineProfileCanBreakButNeverPlace() {
        NavigationOptions options = NavigationOptions.breakingOnly();

        assertTrue(options.allowBreak());
        assertFalse(options.allowPlace());
        assertTrue(options.allowSprint());
        assertFalse(options.scanDroppedItems());
        assertEquals(15, options.mineSearchRadius());
        assertFalse(options.allowInteract());
        assertTrue(options.fastMining());
        assertTrue(options.protectClimbables());
        assertTrue(options.strictBreakWhitelist());
        assertTrue(options.rotateView());
    }

    @Test
    void viewRotationCanBeDisabledWithoutChangingMiningRules() {
        NavigationOptions options = NavigationOptions.breakingOnly()
                .withViewRotation(false);

        assertFalse(options.rotateView());
        assertTrue(options.allowBreak());
        assertFalse(options.allowPlace());
        assertEquals(15, options.mineSearchRadius());
        assertTrue(options.strictBreakWhitelist());
    }
}
