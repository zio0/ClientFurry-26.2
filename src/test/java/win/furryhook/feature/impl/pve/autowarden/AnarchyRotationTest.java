package win.furryhook.feature.impl.pve.autowarden;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnarchyRotationTest {
    @Test
    void penalizesDeathsAndTemporarilyAvoidedServers() {
        AnarchyRotation rotation = new AnarchyRotation();
        long now = 1_000L;
        rotation.onDeath(114);
        rotation.avoidFor(205, 10_000L, now);

        assertEquals(391, rotation.pickNext(List.of(114, 205, 391), 10.0D, now));
    }

    @Test
    void rewardsSuccessfulLootAndDeposits() {
        AnarchyRotation rotation = new AnarchyRotation();
        rotation.onChestLooted(205);
        rotation.onDeposited(205, 10);

        assertTrue(rotation.score(205, 0.0D, 0L)
                > rotation.score(114, 0.0D, 0L));
        assertEquals(1, rotation.snapshot(205).completedCycles());
    }

    @Test
    void rotatesTiesInsteadOfPinningOneServer() {
        AnarchyRotation rotation = new AnarchyRotation();
        List<Integer> candidates = List.of(114, 205);

        int first = rotation.pickNext(candidates, 0.0D, 0L);
        int second = rotation.pickNext(candidates, 0.0D, 0L);

        assertNotEquals(first, second);
    }
}
