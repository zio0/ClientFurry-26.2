package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoAuthCredentialStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void generatedPasswordIsStableAndScoped() throws Exception {
        Path storeDirectory = this.tempDirectory.resolve("auth");
        AutoAuthCredentialStore first = new AutoAuthCredentialStore(storeDirectory);
        AutoAuthCredentialStore.Scope primary =
                new AutoAuthCredentialStore.Scope("PLAY.EXAMPLE.NET:25565", "PlayerOne");

        String password = first.generatedPassword(primary);
        String afterRestart = new AutoAuthCredentialStore(storeDirectory)
                .generatedPassword(primary);
        String otherServer = first.generatedPassword(
                new AutoAuthCredentialStore.Scope("other.example.net", "PlayerOne")
        );
        String otherAccount = first.generatedPassword(
                new AutoAuthCredentialStore.Scope("play.example.net:25565", "PlayerTwo")
        );

        assertEquals(password, afterRestart);
        assertNotEquals(password, otherServer);
        assertNotEquals(password, otherAccount);
        assertTrue(password.matches("(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)[A-Za-z0-9]{20}"));
        assertFalse(Files.exists(first.credentialsPath()),
                "derived credentials do not need a per-server plaintext entry");
    }

    @Test
    void customPasswordIsEncryptedAtRest() throws Exception {
        AutoAuthCredentialStore store =
                new AutoAuthCredentialStore(this.tempDirectory.resolve("auth"));
        AutoAuthCredentialStore.Scope scope =
                new AutoAuthCredentialStore.Scope("play.example.net", "PlayerOne");
        String password = "Private-Password_42";

        store.saveCustomPassword(scope, password);

        assertEquals(password, store.loadCustomPassword(scope).orElseThrow());
        assertEquals(
                password,
                new AutoAuthCredentialStore(this.tempDirectory.resolve("auth"))
                        .loadCustomPassword(scope)
                        .orElseThrow()
        );
        String stored = Files.readString(store.credentialsPath(), StandardCharsets.UTF_8);
        assertFalse(stored.contains(password));
        assertFalse(stored.contains("play.example.net"));
        assertFalse(stored.contains("PlayerOne"));
    }

    @Test
    void appliesOwnerOnlyPermissionsWhenPosixIsAvailable() throws Exception {
        AutoAuthCredentialStore store =
                new AutoAuthCredentialStore(this.tempDirectory.resolve("auth"));
        AutoAuthCredentialStore.Scope scope =
                new AutoAuthCredentialStore.Scope("play.example.net", "PlayerOne");
        store.saveCustomPassword(scope, "Private-Password_42");

        if (Files.getFileAttributeView(
                store.keyPath(),
                PosixFileAttributeView.class
        ) == null) {
            return;
        }

        assertEquals(
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                ),
                Files.getPosixFilePermissions(store.keyPath())
        );
        assertEquals(
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                ),
                Files.getPosixFilePermissions(store.credentialsPath())
        );
        assertEquals(
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                ),
                Files.getPosixFilePermissions(store.keyPath().getParent())
        );
    }
}
