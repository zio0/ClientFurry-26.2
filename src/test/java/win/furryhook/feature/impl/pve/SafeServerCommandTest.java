package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeServerCommandTest {
    @Test
    void acceptsAndNormalizesNavigationCommands() {
        assertEquals("hub", SafeServerCommand.normalize("/HUB").orElseThrow());
        assertEquals("server lobby-1", SafeServerCommand.normalize(" /server   lobby-1 ").orElseThrow());
        assertEquals("home base", SafeServerCommand.normalize("/home base").orElseThrow());
        assertEquals("an 42", SafeServerCommand.normalize("/an 42").orElseThrow());
    }

    @Test
    void rejectsChatInjectionAndUnrelatedCommands() {
        assertTrue(SafeServerCommand.normalize("/msg player secret").isEmpty());
        assertTrue(SafeServerCommand.normalize("/pay player 1000").isEmpty());
        assertTrue(SafeServerCommand.normalize("/hub unexpected").isEmpty());
        assertTrue(SafeServerCommand.normalize("/hub\n/login secret").isEmpty());
        assertTrue(SafeServerCommand.normalize("/server lobby;op").isEmpty());
        assertTrue(SafeServerCommand.normalize(" ").isEmpty());
    }
}
