package win.furryhook.pve.economy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class LocalSyncProtocol {
    public static final int TOKEN_BYTES = 32;
    public static final int MAX_PACKET_BYTES = 768;
    public static final long MAX_CLOCK_SKEW_SECONDS = 20L;

    private static final int MAGIC = 0x424C5359;
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;

    private LocalSyncProtocol() {
    }

    public static byte[] encode(Payload payload,
                                long timestampSeconds,
                                long nonce,
                                byte[] token) throws GeneralSecurityException, IOException {
        requireToken(token);
        ByteArrayOutputStream unsignedBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(unsignedBytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(payload.type().wireId);
            output.writeLong(timestampSeconds);
            output.writeLong(nonce);
            writeUuid(output, payload.sender());
            if (payload instanceof Status status) {
                writeProfile(output, status.profile());
                writePlayerName(output, status.playerName());
                int capabilities = (status.canRequest() ? 1 : 0) | (status.canSend() ? 2 : 0);
                output.writeByte(capabilities);
            } else if (payload instanceof MoneyRequest request) {
                writeUuid(output, request.requestId());
                writeProfile(output, request.profile());
                writePlayerName(output, request.recipient());
                output.writeInt(request.amount());
            }
        }

        byte[] unsigned = unsignedBytes.toByteArray();
        byte[] signature = sign(unsigned, token);
        if (unsigned.length + signature.length > MAX_PACKET_BYTES) {
            throw new IOException("Synchronization packet is too large");
        }
        ByteArrayOutputStream packet = new ByteArrayOutputStream(unsigned.length + signature.length);
        packet.write(unsigned);
        packet.write(signature);
        return packet.toByteArray();
    }

    public static Optional<Envelope> decode(byte[] packet,
                                            byte[] token,
                                            long nowSeconds) {
        if (packet == null
                || packet.length <= MAC_BYTES
                || packet.length > MAX_PACKET_BYTES
                || token == null
                || token.length != TOKEN_BYTES) {
            return Optional.empty();
        }
        int unsignedLength = packet.length - MAC_BYTES;
        byte[] unsigned = new byte[unsignedLength];
        byte[] receivedMac = new byte[MAC_BYTES];
        System.arraycopy(packet, 0, unsigned, 0, unsignedLength);
        System.arraycopy(packet, unsignedLength, receivedMac, 0, MAC_BYTES);

        try {
            if (!MessageDigest.isEqual(receivedMac, sign(unsigned, token))) {
                return Optional.empty();
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(unsigned))) {
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                    return Optional.empty();
                }
                Type type = Type.fromWire(input.readUnsignedByte());
                if (type == null) {
                    return Optional.empty();
                }
                long timestamp = input.readLong();
                if (timestamp < nowSeconds - MAX_CLOCK_SKEW_SECONDS
                        || timestamp > nowSeconds + MAX_CLOCK_SKEW_SECONDS) {
                    return Optional.empty();
                }
                long nonce = input.readLong();
                UUID sender = readUuid(input);
                Payload payload;
                if (type == Type.STATUS) {
                    String profile = readProfile(input);
                    String playerName = input.readUTF();
                    int capabilities = input.readUnsignedByte();
                    if (!EconomyTextParser.isSafePlayerName(playerName)
                            || (capabilities & ~3) != 0) {
                        return Optional.empty();
                    }
                    payload = new Status(
                            sender,
                            profile,
                            playerName,
                            (capabilities & 1) != 0,
                            (capabilities & 2) != 0
                    );
                } else {
                    UUID requestId = readUuid(input);
                    String profile = readProfile(input);
                    String recipient = input.readUTF();
                    int amount = input.readInt();
                    if (!EconomyTextParser.isSafePlayerName(recipient) || amount <= 0) {
                        return Optional.empty();
                    }
                    payload = new MoneyRequest(sender, requestId, profile, recipient, amount);
                }
                if (input.available() != 0) {
                    return Optional.empty();
                }
                return Optional.of(new Envelope(timestamp, nonce, payload));
            }
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static byte[] sign(byte[] data, byte[] token) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static void requireToken(byte[] token) {
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("Synchronization token must contain 32 bytes");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeProfile(DataOutputStream output, String value) throws IOException {
        String profile = value == null ? "" : value;
        if (!profile.matches("[A-Z_]{1,24}")) {
            throw new IOException("Invalid server profile");
        }
        output.writeUTF(profile);
    }

    private static String readProfile(DataInputStream input) throws IOException {
        String profile = input.readUTF();
        if (!profile.matches("[A-Z_]{1,24}")) {
            throw new IOException("Invalid server profile");
        }
        return profile;
    }

    private static void writePlayerName(DataOutputStream output, String value) throws IOException {
        if (!EconomyTextParser.isSafePlayerName(value)) {
            throw new IOException("Invalid player name");
        }
        output.writeUTF(value);
    }

    public sealed interface Payload permits Status, MoneyRequest {
        UUID sender();

        Type type();
    }

    public record Status(
            UUID sender,
            String profile,
            String playerName,
            boolean canRequest,
            boolean canSend
    ) implements Payload {
        @Override
        public Type type() {
            return Type.STATUS;
        }
    }

    public record MoneyRequest(
            UUID sender,
            UUID requestId,
            String profile,
            String recipient,
            int amount
    ) implements Payload {
        @Override
        public Type type() {
            return Type.MONEY_REQUEST;
        }
    }

    public record Envelope(long timestampSeconds, long nonce, Payload payload) {
    }

    public enum Type {
        STATUS(1),
        MONEY_REQUEST(2);

        private final int wireId;

        Type(int wireId) {
            this.wireId = wireId;
        }

        private static Type fromWire(int wireId) {
            for (Type value : values()) {
                if (value.wireId == wireId) {
                    return value;
                }
            }
            return null;
        }
    }
}
