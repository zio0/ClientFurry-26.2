package win.furryhook.pve.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerProfileTest {
    @Test
    void recognizesSupportedServerFamilies() {
        assertEquals(ServerProfile.FUNTIME,
                ServerProfile.detect("FunTime", "play.funtime.su"));
        assertEquals(ServerProfile.HOLYWORLD,
                ServerProfile.detect("HolyWorld", "play.holyworld.ru"));
        assertEquals(ServerProfile.REALLYWORLD,
                ServerProfile.detect("ReallyWorld", "rw.example"));
        assertEquals(ServerProfile.REALLYWORLD,
                ServerProfile.detect("SpookyTime", "play.example"));
    }

    @Test
    void unknownAndNullIdentityAreGeneric() {
        assertEquals(ServerProfile.GENERIC,
                ServerProfile.detect("Vanilla", "localhost"));
        assertEquals(ServerProfile.GENERIC,
                ServerProfile.detect(null, null));
    }
}
