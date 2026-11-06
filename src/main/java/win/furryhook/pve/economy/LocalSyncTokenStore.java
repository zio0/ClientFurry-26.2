package win.furryhook.pve.economy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public final class LocalSyncTokenStore {
    private static final String FILE_NAME = "local-sync.token";
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private LocalSyncTokenStore() {
    }

    public static byte[] loadOrCreate(Path configDirectory) throws IOException {
        if (configDirectory == null) {
            throw new IOException("Missing synchronization configuration directory");
        }
        Files.createDirectories(configDirectory);
        Path tokenFile = configDirectory.resolve(FILE_NAME);
        if (Files.exists(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            return read(tokenFile);
        }

        byte[] generated = new byte[LocalSyncProtocol.TOKEN_BYTES];
        new SecureRandom().nextBytes(generated);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(generated);
        try {
            Files.writeString(
                    tokenFile,
                    encoded,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            restrictPermissions(tokenFile);
            return generated;
        } catch (FileAlreadyExistsException race) {
            return read(tokenFile);
        }
    }

    private static byte[] read(Path tokenFile) throws IOException {
        if (Files.isSymbolicLink(tokenFile)
                || !Files.isRegularFile(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Synchronization token path is not a regular file");
        }
        restrictPermissions(tokenFile);
        String encoded = Files.readString(tokenFile, StandardCharsets.US_ASCII).trim();
        try {
            byte[] token = Base64.getUrlDecoder().decode(encoded);
            if (token.length != LocalSyncProtocol.TOKEN_BYTES) {
                throw new IOException("Synchronization token has an invalid length");
            }
            return token;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Synchronization token is malformed", exception);
        }
    }

    private static void restrictPermissions(Path tokenFile) throws IOException {
        try {
            Files.setPosixFilePermissions(tokenFile, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {

        }
    }
}
