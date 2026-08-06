package com.minigoogle.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ConsistentHashRing functionality. */
class ClusterTest {

    @Test
    void testConsistentHashRing() {
        ConsistentHashRing ring = new ConsistentHashRing();
        ring.addNode("node-A");
        ring.addNode("node-B");
        ring.addNode("node-C");

        // Same key always maps to same node
        String node1 = ring.getNode("test-key");
        String node2 = ring.getNode("test-key");
        assertEquals(node1, node2);

        // Different keys may map to different nodes
        int differentCount = 0;
        for (int i = 0; i < 100; i++) {
            String n = ring.getNode("key-" + i);
            if (!n.equals(node1)) differentCount++;
        }
        assertTrue(differentCount > 0);
    }

    @Test
    void testConsistentHashRingRemoveNode() {
        ConsistentHashRing ring = new ConsistentHashRing();
        ring.addNode("A");
        ring.addNode("B");
        ring.addNode("C");

        String before = ring.getNode("some-key");
        ring.removeNode("B");
        String after = ring.getNode("some-key");

        // Most keys should still map to the same node after removal
        // (only keys that were on B need to move)
        assertNotNull(after);
    }

    @Test
    void testConsistentHashRingGetNodes() {
        ConsistentHashRing ring = new ConsistentHashRing();
        ring.addNode("A");
        ring.addNode("B");
        ring.addNode("C");

        List<String> nodes = ring.getNodes("key", 3);
        assertEquals(3, nodes.size());
        assertEquals(3, ring.nodeCount());
    }

    @Test
    void testRaftConsensus() {
        RaftConsensus raft = new RaftConsensus("node-1");
        assertEquals(RaftConsensus.RaftState.FOLLOWER, raft.getState());

        raft.startElection();
        assertEquals(RaftConsensus.RaftState.CANDIDATE, raft.getState());
        assertEquals(1, raft.getCurrentTerm());

        raft.becomeLeader();
        assertEquals(RaftConsensus.RaftState.LEADER, raft.getState());
        assertEquals("node-1", raft.getCurrentLeader());
    }

    @Test
    void testRaftVoteRequest() {
        RaftConsensus raft = new RaftConsensus("node-1");
        raft.startElection();

        RaftConsensus other = new RaftConsensus("node-2");
        // Higher term should win vote
        assertTrue(other.receiveVoteRequest("node-1", 2));
        // Same term should not win vote (already voted for self implicitly)
        assertFalse(raft.receiveVoteRequest("node-2", 1));
    }

    @Test
    void testRaftVoteNotGrantedTwiceInSameTerm() {
        RaftConsensus voter = new RaftConsensus("voter");
        assertTrue(voter.receiveVoteRequest("candidate-a", 1));
        assertFalse(voter.receiveVoteRequest("candidate-b", 1));
        // A higher term still wins
        assertTrue(voter.receiveVoteRequest("candidate-c", 2));
    }

    @Test
    void testGossipProtocol() {
        GossipProtocol gossip = new GossipProtocol("node-1");
        assertEquals(1, gossip.memberCount());
        assertTrue(gossip.getLiveNodes().contains("node-1"));

        gossip.heartbeat();
        assertEquals(1, gossip.getLiveNodes().size());
    }

    @Test
    void testClusterSecurity() {
        ClusterSecurity security = new ClusterSecurity("shared-secret");
        String token = security.generateToken("node-1");
        assertNotNull(token);
        assertTrue(security.validateToken("node-1", token));
        assertFalse(security.validateToken("node-1", "wrong-token"));
        assertFalse(security.validateToken("node-2", token));
    }

    @Test
    void testClusterSecurityBearerToken() {
        ClusterSecurity security = new ClusterSecurity("shared-secret");
        security.generateToken("node-1");

        String nodeId = security.validateBearerToken("Bearer " + security.generateToken("node-1"));
        // The second generateToken creates a different token, so this should be null
        // unless we use the original token
        // Let's test with a known token
        String originalToken = security.generateToken("node-2");
        assertEquals("node-2", security.validateBearerToken("Bearer " + originalToken));
        assertNull(security.validateBearerToken("invalid"));
    }

    @Test
    void testDeriveTokenIsDeterministicPerNode() {
        ClusterSecurity security = new ClusterSecurity("shared-secret");

        assertEquals(security.deriveToken("node-1"), security.deriveToken("node-1"));
        assertNotEquals(security.deriveToken("node-1"), security.deriveToken("node-2"));
        // A second instance sharing the secret derives the same token (peer verification)
        assertEquals(security.deriveToken("node-1"), new ClusterSecurity("shared-secret").deriveToken("node-1"));
        assertNotEquals(security.deriveToken("node-1"), new ClusterSecurity("other-secret").deriveToken("node-1"));
    }

    @Test
    void testAuthenticateAcceptsClaimedNodeAndRejectsForgeries() {
        ClusterSecurity security = new ClusterSecurity("shared-secret");

        // Valid claim: token derived for the claimed node ID
        assertEquals("node-1", security.authenticate("Bearer " + security.deriveToken("node-1"), "node-1"));
        // Missing auth header
        assertNull(security.authenticate(null, "node-1"));
        // Non-Bearer scheme
        assertNull(security.authenticate("Basic abc", "node-1"));
        // Token for a different node than claimed
        assertNull(security.authenticate("Bearer " + security.deriveToken("node-2"), "node-1"));
        // Unknown secret produces an invalid token
        assertNull(security.authenticate("Bearer " + new ClusterSecurity("other-secret").deriveToken("node-1"), "node-1"));
        // Registered (minted) tokens still authenticate, regardless of the claim
        String minted = security.generateToken("node-3");
        assertEquals("node-3", security.authenticate("Bearer " + minted, "anything"));
    }
}
