package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModeratorDetectorTest {
    @Test
    void recognizesDelimitedStaffRoles() {
        assertTrue(ModeratorDetector.containsRole("\u00a7c[ADMIN] Player"));
        assertTrue(ModeratorDetector.containsRole("[Sr.Mod] Player"));
        assertTrue(ModeratorDetector.containsRole("[Модератор] Игрок"));
        assertTrue(ModeratorDetector.containsRole("team_helper"));
        assertTrue(ModeratorDetector.containsRole("[Стажёр] Игрок"));
    }

    @Test
    void avoidsRoleSubstringsInsideOrdinaryWords() {
        assertFalse(ModeratorDetector.containsRole("ModeratorFan"));
        assertFalse(ModeratorDetector.containsRole("modern_player"));
        assertFalse(ModeratorDetector.containsRole("administration_building"));
        assertFalse(ModeratorDetector.containsRole("ordinary player"));
        assertFalse(ModeratorDetector.containsRole(null));
    }
}
