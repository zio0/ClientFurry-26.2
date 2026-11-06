package win.furryhook.feature.impl.pve.autowarden;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoWardenParsersTest {
    @Test
    void validatesAndDeduplicatesAnarchyConfiguration() {
        assertEquals(114, AutoWardenParsers.parseHomeAnarchy("114").orElseThrow());
        assertTrue(AutoWardenParsers.parseHomeAnarchy("99").isEmpty());
        assertEquals(
                List.of(205, 391),
                AutoWardenParsers.parseLootAnarchies("205, 114, 391, 205", 114)
        );
    }

    @Test
    void parsesChestTimersAndOpenState() {
        var timer = AutoWardenParsers.parseChestTimer("Сундук откроется через 02:15")
                .orElseThrow();
        assertEquals(135, timer.remainingSeconds());
        assertFalse(timer.openNow());

        var open = AutoWardenParsers.parseChestTimer("Сундук открыт").orElseThrow();
        assertEquals(0, open.remainingSeconds());
        assertTrue(open.openNow());
    }

    @Test
    void parsesCombatAndAttackerMessages() {
        assertEquals(
                13_000L,
                AutoWardenParsers.parseCombatHoldMillis(
                        "Вы недавно были в бою, телепорт через 12 секунд"
                ).orElseThrow()
        );
        assertEquals(
                "Enemy_1",
                AutoWardenParsers.parseAttacker("Вы были убиты игроком Enemy_1")
                        .orElseThrow()
        );
    }
}
