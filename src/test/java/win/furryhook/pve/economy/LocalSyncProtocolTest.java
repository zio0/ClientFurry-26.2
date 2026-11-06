package win.furryhook.pve.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSyncProtocolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void authenticatesAndDecodesStatus() throws Exception {
        byte[] token = token((byte) 7);
        UUID sender = UUID.randomUUID();
        LocalSyncProtocol.Status status = new LocalSyncProtocol.Status(
                sender,
                "FUNTIME",
                "Player_1",
                true,
                true
        );
        byte[] packet = LocalSyncProtocol.encode(status, 1_000L, 42L, token);

        LocalSyncProtocol.Envelope envelope =
                LocalSyncProtocol.decode(packet, token, 1_005L).orElseThrow();
        assertEquals(42L, envelope.nonce());
        assertEquals(status, envelope.payload());
    }

    @Test
    void rejectsTamperingWrongTokenAndStalePackets() throws Exception {
        byte[] token = token((byte) 3);
        LocalSyncProtocol.Status status = new LocalSyncProtocol.Status(
                UUID.randomUUID(),
                "REALLYWORLD",
                "LocalPlayer",
                true,
                false
        );
        byte[] packet = LocalSyncProtocol.encode(status, 2_000L, 99L, token);

        byte[] tampered = packet.clone();
        tampered[12] ^= 0x40;
        assertTrue(LocalSyncProtocol.decode(tampered, token, 2_000L).isEmpty());
        assertTrue(LocalSyncProtocol.decode(packet, token((byte) 4), 2_000L).isEmpty());
        assertTrue(LocalSyncProtocol.decode(packet, token, 2_100L).isEmpty());
    }

    @Test
    void moneyRequestCarriesOnlyTypedFields() throws Exception {
        byte[] token = token((byte) 11);
        LocalSyncProtocol.MoneyRequest request = new LocalSyncProtocol.MoneyRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "FUNTIME",
                "Receiver_2",
                250_000
        );
        byte[] packet = LocalSyncProtocol.encode(request, 3_000L, 123L, token);

        LocalSyncProtocol.Envelope envelope =
                LocalSyncProtocol.decode(packet, token, 3_000L).orElseThrow();
        assertEquals(request, assertInstanceOf(
                LocalSyncProtocol.MoneyRequest.class,
                envelope.payload()
        ));
    }

    @Test
    void tokenStoreReusesGeneratedOwnerToken() throws Exception {
        byte[] first = LocalSyncTokenStore.loadOrCreate(temporaryDirectory);
        byte[] second = LocalSyncTokenStore.loadOrCreate(temporaryDirectory);

        assertEquals(LocalSyncProtocol.TOKEN_BYTES, first.length);
        assertArrayEquals(first, second);
        assertFalse(Arrays.equals(first, new byte[LocalSyncProtocol.TOKEN_BYTES]));
    }

    @Test
    void paymentLedgerAllowsExactlyOneClaimPerRequest() {
        LocalPaymentLedger ledger = new LocalPaymentLedger(temporaryDirectory);
        UUID request = UUID.randomUUID();

        assertTrue(ledger.claim(request, 10_000L));
        assertFalse(ledger.claim(request, 10_001L));
        assertTrue(ledger.claim(UUID.randomUUID(), 10_001L));
    }

    private static byte[] token(byte value) {
        byte[] token = new byte[LocalSyncProtocol.TOKEN_BYTES];
        Arrays.fill(token, value);
        return token;
    }
}
