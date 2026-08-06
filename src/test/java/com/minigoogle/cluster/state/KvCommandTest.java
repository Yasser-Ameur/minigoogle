package com.minigoogle.cluster.state;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    @Test
    void testTxnRoundTripPreservesOrder() {
        byte[] v1 = "v1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);
        byte[] txn = KvCommand.encodeTxn(List.of(
                new KvCommand.TxnOp(KvCommand.OP_PUT, "a", v1),
                new KvCommand.TxnOp(KvCommand.OP_PUT, "b", v2),
                new KvCommand.TxnOp(KvCommand.OP_DELETE, "c", null)));

        List<KvCommand.DecodedCommand> ops = KvCommand.decodeTxn(txn);
        assertEquals(3, ops.size());
        assertEquals(KvCommand.OP_PUT, ops.get(0).op());
        assertEquals("a", ops.get(0).key());
        assertArrayEquals(v1, ops.get(0).value());
        assertEquals(KvCommand.OP_PUT, ops.get(1).op());
        assertEquals("b", ops.get(1).key());
        assertArrayEquals(v2, ops.get(1).value());
        assertEquals(KvCommand.OP_DELETE, ops.get(2).op());
        assertEquals("c", ops.get(2).key());
        assertNull(ops.get(2).value());
    }

    @Test
    void testTxnUnicodeKeysRoundTrip() {
        byte[] txn = KvCommand.encodeTxn(List.of(
                new KvCommand.TxnOp(KvCommand.OP_PUT, "clé", new byte[]{9}),
                new KvCommand.TxnOp(KvCommand.OP_DELETE, "再见", null)));

        List<KvCommand.DecodedCommand> ops = KvCommand.decodeTxn(txn);
        assertEquals("clé", ops.get(0).key());
        assertEquals("再见", ops.get(1).key());
    }

    @Test
    void testTxnEmptyRejected() {
        assertThrows(IllegalArgumentException.class, () -> KvCommand.encodeTxn(List.of()));
    }

    @Test
    void testTxnPutMissingValueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> KvCommand.encodeTxn(List.of(new KvCommand.TxnOp(KvCommand.OP_PUT, "k", null))));
    }

    @Test
    void testTxnUnknownOpRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> KvCommand.encodeTxn(List.of(new KvCommand.TxnOp((byte) 0x63, "k", null))));
        byte[] bad = {KvCommand.OP_TXN, 0, 0, 0, 1, 0x63, 0, 1, 'k'};
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decodeTxn(bad));
    }

    @Test
    void testTxnTrailingBytesRejected() {
        byte[] good = KvCommand.encodeTxn(List.of(new KvCommand.TxnOp(KvCommand.OP_DELETE, "k", null)));
        byte[] padded = new byte[good.length + 1];
        System.arraycopy(good, 0, padded, 0, good.length);
        padded[good.length] = 0;
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decodeTxn(padded));
    }

    @Test
    void testTxnTruncatedRejected() {
        byte[] good = KvCommand.encodeTxn(List.of(
                new KvCommand.TxnOp(KvCommand.OP_PUT, "k", new byte[]{1, 2, 3})));
        byte[] truncated = new byte[good.length - 2];
        System.arraycopy(good, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decodeTxn(truncated));
    }

    @Test
    void testTxnFrameDecodedAsSingleRejected() {
        byte[] txn = KvCommand.encodeTxn(List.of(new KvCommand.TxnOp(KvCommand.OP_PUT, "k", new byte[]{1})));
        assertTrue(KvCommand.isTxnFrame(txn));
        assertThrows(IllegalArgumentException.class, () -> KvCommand.decode(txn));
    }
}
