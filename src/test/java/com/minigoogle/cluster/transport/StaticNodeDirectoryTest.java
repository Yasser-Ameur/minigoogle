package com.minigoogle.cluster.transport;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production peer-address parser. Every cluster transport resolves peers
 * through this, so a malformed peer list must fail loudly at startup rather than
 * silently producing a node that cannot reach its cluster.
 */
class StaticNodeDirectoryTest {

    @Test
    void parsesExplicitIdAndUri() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse(
                "node-a=http://host-a:8081,node-b=http://host-b:8081");

        assertEquals(URI.create("http://host-a:8081"), dir.getBaseUri("node-a"));
        assertEquals(URI.create("http://host-b:8081"), dir.getBaseUri("node-b"));
        assertEquals(2, dir.size());
    }

    @Test
    void parsesAtSyntaxAndAssumesHttp() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse("node-a@host-a:8081");
        assertEquals(URI.create("http://host-a:8081"), dir.getBaseUri("node-a"));
    }

    @Test
    void defaultsNodeIdToHostWhenNotGiven() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse("http://minigoogle-0:8081,minigoogle-1:8081");
        assertEquals(URI.create("http://minigoogle-0:8081"), dir.getBaseUri("minigoogle-0"));
        assertEquals(URI.create("http://minigoogle-1:8081"), dir.getBaseUri("minigoogle-1"));
    }

    @Test
    void ignoresBlankSegmentsAndWhitespace() {
        // A trailing comma in a compose file or ConfigMap must not break startup.
        StaticNodeDirectory dir = StaticNodeDirectory.parse(" node-a=http://a:1 , , node-b=http://b:2 ,");
        assertEquals(2, dir.size());
        assertEquals(URI.create("http://a:1"), dir.getBaseUri("node-a"));
        assertEquals(URI.create("http://b:2"), dir.getBaseUri("node-b"));
    }

    @Test
    void emptyPeerListIsValidForASingleNodeCluster() {
        assertEquals(0, StaticNodeDirectory.parse(null).size());
        assertEquals(0, StaticNodeDirectory.parse("").size());
        assertEquals(0, StaticNodeDirectory.parse("   ").size());
    }

    @Test
    void unknownNodeResolvesToNull() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse("node-a=http://a:1");
        assertNull(dir.getBaseUri("node-z"));
    }

    @Test
    void preservesConfigurationOrder() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse(
                "c=http://c:1,a=http://a:1,b=http://b:1");
        assertEquals(List.of("c", "a", "b"), List.copyOf(dir.nodeIds()));
    }

    @Test
    void stripsAnyPathSoTransportsCanAppendTheirOwn() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse("node-a=http://host-a:8081/cluster/");
        assertEquals(URI.create("http://host-a:8081"), dir.getBaseUri("node-a"));
    }

    @Test
    void withSelfAddsThisNodesOwnAddress() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse("node-b=http://b:8081")
                .withSelf("node-a", URI.create("http://a:8081"));
        assertEquals(URI.create("http://a:8081"), dir.getBaseUri("node-a"));
        assertEquals(URI.create("http://b:8081"), dir.getBaseUri("node-b"));
    }

    @Test
    void rejectsPeerWithoutAnExplicitPort() {
        // Guessing a port would produce a node that starts cleanly and then
        // silently fails every RPC.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> StaticNodeDirectory.parse("node-a=http://host-a"));
        assertTrue(e.getMessage().contains("port"), e.getMessage());
    }

    @Test
    void rejectsConflictingAddressesForTheSameNode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> StaticNodeDirectory.parse("node-a=http://a:1,node-a=http://a:2"));
        assertTrue(e.getMessage().contains("two addresses"), e.getMessage());
    }

    @Test
    void duplicateIdenticalEntriesAreAccepted() {
        StaticNodeDirectory dir = StaticNodeDirectory.parse("node-a=http://a:1,node-a=http://a:1");
        assertEquals(1, dir.size());
    }
}
