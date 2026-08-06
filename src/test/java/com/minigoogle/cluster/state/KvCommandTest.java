package com.minigoogle.cluster.state;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the replicated key-value command envelope. */
class KvCommandTest {

    @Test
    void testPutRoundTrip() {
        byte[] value = {1, 2, 3};
        KvCommand.DecodedCommand command = KvCommand.decode(KvCommand.encodePut("doc:1", value));

        assertEquals(KvCommand.OP_PUT, command.op());
        assertEquals("doc:1", command.key());
        assertArrayEquals(value, command.value());
    }

    @Test
    void testPutEmptyValueRoundTrip() {
        KvCommand.DecodedCommand command = KvCommand.decode(KvCommand.encodePut("k", new byte[0]));

        assertEquals(KvCommand.OP_PUT, command.op());
        assertEquals("k", command.key());
        assertEquals(0, command.value().length);
    }

    @Test
    void testPutUnicodeKeyRoundTrip() {
        KvCommand.DecodedCommand command = KvCommand.decode(KvCommand.encodePut("clé", new byte[]{9}));

        assertEquals("clé", command.key());
    }

    @Test
    void testDeleteRoundTrip() {
        KvCommand.DecodedCommand command = KvCommand.decode(KvCommand.encodeDelete("doc:9"));

        assertEquals(KvCommand.OP_DELETE, command.op());
        assertEquals("doc:9", command.key());
        assertNull(command.value());
    }

    @Test
    void testKeyTooLongRejected() {
        String longKey = "k".repeat(70_000);
        assertThrows(IllegalArgumentException.class, () -> KvCommand.encodePut(longKey, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> KvCommand.encodeDelete(longKey));
    }

    @Test
    void testUnknownOpRejected() {
        byte[] bad = {0x63, 0, 1, 'k'};
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decode(bad));
    }

    @Test
    void testTruncatedPutRejected() {
        byte[] good = KvCommand.encodePut("k", new byte[]{1, 2, 3});
        byte[] truncated = new byte[good.length - 2];
        System.arraycopy(good, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decode(truncated));
    }

    @Test
    void testValueLengthMismatchRejected() {
        byte[] bad = {KvCommand.OP_PUT, 0, 1, 'k', 0, 0, 0, 9, 1, 2};
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decode(bad));
    }

    @Test
    void testTrailingBytesRejected() {
        byte[] good = KvCommand.encodeDelete("k");
        byte[] padded = new byte[good.length + 1];
        System.arraycopy(good, 0, padded, 0, good.length);
        padded[good.length] = 0;
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decode(padded));
    }

    @Test
    void testEmptyPayloadRejected() {
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decode(new byte[0]));
    }

    @Test
    void testValueRoundTripsThroughBytes() {
        byte[] value = "hello world".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(value, KvCommand.decode(KvCommand.encodePut("greeting", value)).value());
    }
}
