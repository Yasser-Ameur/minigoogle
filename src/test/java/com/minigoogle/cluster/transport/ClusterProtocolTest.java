package com.minigoogle.cluster.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterProtocolTest {

    @Test
    void testCurrentVersionIsAccepted() {
        ClusterProtocol.validateVersion(ClusterProtocol.PROTOCOL_VERSION);
    }

    @Test
    void testUnsupportedVersionIsRejected() {
        ProtocolViolationException ex = assertThrows(ProtocolViolationException.class,
                () -> ClusterProtocol.validateVersion(ClusterProtocol.PROTOCOL_VERSION + 1));
        assertTrue(ex.getMessage().contains("Unsupported protocol version"));
    }

    @Test
    void testIdsAreUnique() {
        assertNotEquals(ClusterProtocol.newId(), ClusterProtocol.newId());
        assertNotNull(ClusterProtocol.newId());
    }
}
