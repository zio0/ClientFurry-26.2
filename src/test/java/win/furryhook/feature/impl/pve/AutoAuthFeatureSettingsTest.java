package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoAuthFeatureSettingsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void customPasswordIsSecretAndExcludedFromFeatureConfig() {
        AutoAuthFeature feature = new AutoAuthFeature(
                new AutoAuthCredentialStore(this.tempDirectory.resolve("auth"))
        );

        assertTrue(feature.customPassword.isSecret());
        assertFalse(feature.customPassword.isPersistent());
    }

    @Test
    void passwordValidationRejectsCommandBreakingValues() {
        assertTrue(AutoAuthFeature.isValidPassword("Strong-Password_42"));
        assertFalse(AutoAuthFeature.isValidPassword("abc"));
        assertFalse(AutoAuthFeature.isValidPassword("contains whitespace"));
        assertFalse(AutoAuthFeature.isValidPassword("line\nbreak"));
        assertFalse(AutoAuthFeature.isValidPassword("/login"));
    }
}
