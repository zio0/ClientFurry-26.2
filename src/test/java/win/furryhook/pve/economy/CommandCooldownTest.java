package win.furryhook.pve.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCooldownTest {
    @Test
    void acquiresOnlyAtOrAfterDeterministicDeadline() {
        CommandCooldown cooldown = new CommandCooldown();

        assertTrue(cooldown.tryAcquire(100L, 20L));
        assertFalse(cooldown.tryAcquire(119L, 20L));
        assertTrue(cooldown.tryAcquire(120L, 20L));
        cooldown.defer(121L, 50L);
        assertFalse(cooldown.ready(170L));
        assertTrue(cooldown.ready(171L));
    }
}
