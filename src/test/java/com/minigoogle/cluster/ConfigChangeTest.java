package com.minigoogle.cluster;

import com.minigoogle.cluster.ConfigChange.ChangeType;
import com.minigoogle.cluster.state.KvCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the Raft config-change framing. */
class ConfigChangeTest {

    @Test
    void testAddRoundTrip() {
        ConfigChange change = new ConfigChange(ChangeType.ADD, "node-4");
        byte[] frame = change.encode();

        ConfigChange decoded = ConfigChange.decode(frame);
        assertEquals(ChangeType.ADD, decoded.type());
        assertEquals("node-4", decoded.nodeId());
    }

    @Test
    void testRemoveRoundTrip() {
        ConfigChange change = new ConfigChange(ChangeType.REMOVE, "node-3");
        byte[] frame = change.encode();

        ConfigChange decoded = ConfigChange.decode(frame);
        assertEquals(ChangeType.REMOVE, decoded.type());
        assertEquals("node-3", decoded.nodeId());
    }

    @Test
    void testFrameLayout() {
        byte[] frame = new ConfigChange(ChangeType.ADD, "ab").encode();
        assertEquals(ConfigChange.OP_CONFIG, frame[0]);
        assertEquals(ConfigChange.OP_ADD, frame[1]);
        assertEquals(0, frame[2]);
        assertEquals(2, frame[3]);
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOfRange(frame, 4, 6));
    }

    @Test
    void testIsConfigFrame() {
        assertTrue(ConfigChange.isConfigFrame(new ConfigChange(ChangeType.ADD, "n").encode()));
        assertTrue(ConfigChange.isConfigFrame(new ConfigChange(ChangeType.REMOVE, "n").encode()));
        assertFalse(ConfigChange.isConfigFrame(KvCommand.encodePut("k", new byte[]{1})));
        assertFalse(ConfigChange.isConfigFrame(KvCommand.encodeDelete("k")));
        assertFalse(ConfigChange.isConfigFrame(new byte[]{0x00}));
        assertFalse(ConfigChange.isConfigFrame(null));
        assertFalse(ConfigChange.isConfigFrame(new byte[0]));
    }

    @Test
    void testDecodeUnknownOpFailsFast() {
        byte[] frame = {ConfigChange.OP_CONFIG, 0x09, 0, 1, 'n'};
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(frame));
    }

    @Test
    void testDecodeNotConfigFrameFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(KvCommand.encodePut("k", new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(new byte[]{0x00, 0x01}));
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(null));
    }

    @Test
    void testDecodeTruncatedFailsFast() {
        byte[] frame = new ConfigChange(ChangeType.ADD, "node-9").encode();
        byte[] truncated = java.util.Arrays.copyOf(frame, frame.length - 1);
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(truncated));
    }

    @Test
    void testDecodeTrailingBytesFailFast() {
        byte[] frame = new ConfigChange(ChangeType.ADD, "n").encode();
        byte[] padded = java.util.Arrays.copyOf(frame, frame.length + 1);
        padded[frame.length] = 0;
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(padded));
    }

    @Test
    void testDecodeZeroLengthNodeIdFailsFast() {
        byte[] frame = {ConfigChange.OP_CONFIG, ConfigChange.OP_ADD, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> ConfigChange.decode(frame));
    }

    @Test
    void testEncodeOversizedNodeIdFailsFast() {
        String huge = "x".repeat(0x10000);
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigChange(ChangeType.ADD, huge).encode());
    }
}
