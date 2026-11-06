package win.furryhook.pve.economy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class LocalPaymentLedger {
    private static final long RETENTION_SECONDS = 86_400L;
    private static final int MAX_BYTES = 131_072;

    private final Path file;

    public LocalPaymentLedger(Path configDirectory) {
        this.file = configDirectory.resolve("local-sync-payments.ledger");
    }

    public synchronized boolean claim(UUID requestId, long nowSeconds) {
        if (requestId == null) {
            return false;
        }
        try {
            Files.createDirectories(this.file.getParent());
            try (FileChannel channel = FileChannel.open(
                    this.file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            ); FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    return false;
                }
                Map<UUID, Long> entries = read(channel);
                entries.entrySet().removeIf(entry ->
                        entry.getValue() < nowSeconds - RETENTION_SECONDS
                );
                if (entries.containsKey(requestId)) {
                    return false;
                }
                entries.put(requestId, nowSeconds);
                write(channel, entries);
                return true;
            }
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Map<UUID, Long> read(FileChannel channel) throws IOException {
        LinkedHashMap<UUID, Long> entries = new LinkedHashMap<>();
        long size = channel.size();
        if (size <= 0L || size > MAX_BYTES) {
            return entries;
        }
        ByteBuffer bytes = ByteBuffer.allocate((int) size);
        channel.position(0L);
        while (bytes.hasRemaining() && channel.read(bytes) >= 0) {

        }
        String content = new String(bytes.array(), StandardCharsets.US_ASCII);
        for (String line : content.split("\\R")) {
            String[] parts = line.trim().split(" ", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                entries.put(UUID.fromString(parts[1]), Long.parseLong(parts[0]));
            } catch (IllegalArgumentException ignored) {

            }
        }
        return entries;
    }

    private static void write(FileChannel channel, Map<UUID, Long> entries) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<UUID, Long> entry : entries.entrySet()) {
            content.append(entry.getValue())
                    .append(' ')
                    .append(entry.getKey())
                    .append('\n');
        }
        byte[] bytes = content.toString().getBytes(StandardCharsets.US_ASCII);
        channel.truncate(0L);
        channel.position(0L);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
    }
}
