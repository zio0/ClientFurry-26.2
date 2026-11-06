package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiAfkActionTimerTest {
    @Test
    void becomesDueExactlyAtConfiguredInterval() {
        AntiAfkActionTimer timer = new AntiAfkActionTimer();

        for (int tick = 0; tick < 39; tick++) {
            timer.advance();
        }
        assertFalse(timer.isDue(40));

        timer.advance();
        assertTrue(timer.isDue(40));
    }

    @Test
    void remainsDueUntilAnActionIsPerformed() {
        AntiAfkActionTimer timer = new AntiAfkActionTimer();
        for (int tick = 0; tick < 25; tick++) {
            timer.advance();
        }

        assertTrue(timer.isDue(20));
        timer.advance();
        assertTrue(timer.isDue(20));

        timer.actionPerformed();
        assertEquals(0L, timer.elapsedTicks());
        assertFalse(timer.isDue(20));
    }

    @Test
    void convertsSecondsToClientTicks() {
        assertEquals(400L, AntiAfkActionTimer.secondsToTicks(20.0D));
        assertEquals(1L, AntiAfkActionTimer.secondsToTicks(0.0D));
    }
}
