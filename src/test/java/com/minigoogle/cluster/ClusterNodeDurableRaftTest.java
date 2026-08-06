package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.NodeDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Raft metadata durability through {@link ClusterNode}: a node
 * constructed on a storage directory persists its term and vote, and a
 * restarted node on the same directory restores them.
 */
class ClusterNodeDurableRaftTest {

    private NodeDirectory directory(int port) {
        return nodeId -> URI.create("http://127.0.0.1:" + port);
    }

    @Test
    void testRaftMetadataSurvivesNodeRestart() throws IOException {
        ClusterSecurity security = new ClusterSecurity("restart-secret");
        Path nodeStorage = Files.createTempDirectory("cluster-node-storage");
        nodeStorage.toFile().deleteOnExit();
        long electionTimeout = 60_000; // keep the node out of auto-election during the test

        ClusterNode node = new ClusterNode("node-a", 9097, directory(9097),
                100, 500, electionTimeout, 150, null, security, nodeStorage);
        node.start();
        try {
            assertTrue(node.getRaft().receiveVoteRequest("candidate-x", 6));
            assertEquals(6, node.getRaft().getCurrentTerm());
            assertTrue(Files.exists(nodeStorage.resolve("raft-metadata.bin")),
                    "The raft metadata file must exist after a vote");
        } finally {
            node.stop();
        }

        // A fresh process on the same storage directory restores term and vote.
        ClusterNode restarted = new ClusterNode("node-a", 9097, directory(9097),
                100, 500, electionTimeout, 150, null, security, nodeStorage);
        restarted.start();
        try {
            assertEquals(6, restarted.getRaft().getCurrentTerm(), "Restarted node must restore its term");
            assertFalse(restarted.getRaft().receiveVoteRequest("candidate-y", 6),
                    "No double vote after restart: a different candidate in term 6 must lose");
            assertFalse(restarted.getRaft().receiveVoteRequest("candidate-z", 4),
                    "A stale-term candidate must never win after restart");
            assertTrue(restarted.getRaft().receiveVoteRequest("candidate-x", 6),
                    "The originally voted-for candidate in the same term is re-granted");
        } finally {
            restarted.stop();
        }
    }

    @Test
    void testWithoutStorageDirectoryRaftMetadataStaysInMemory() throws IOException {
        ClusterSecurity security = new ClusterSecurity("restart-secret");

        ClusterNode node = new ClusterNode("node-b", 9098, directory(9098),
                100, 500, 60_000, 150, null, security);
        node.start();
        try {
            assertEquals(0, node.getRaft().getCurrentTerm(), "A fresh node starts at term 0");
            assertTrue(node.getRaft().receiveVoteRequest("candidate-w", 3));
            assertEquals(3, node.getRaft().getCurrentTerm());
        } finally {
            node.stop();
        }
    }

    @Test
    void testRaftLogSurvivesNodeRestart() throws IOException {
        ClusterSecurity security = new ClusterSecurity("restart-secret");
        Path nodeStorage = Files.createTempDirectory("cluster-node-log-storage");
        nodeStorage.toFile().deleteOnExit();
        long electionTimeout = 60_000; // keep the node out of auto-election during the test

        ClusterNode node = new ClusterNode("node-c", 9099, directory(9099),
                100, 500, electionTimeout, 150, null, security, nodeStorage);
        node.start();
        try {
            // Drive the leader manually, then append an entry through the real
            // append pipeline so it is fsynced to the raft log WAL.
            node.getRaft().startElection();
            assertTrue(node.getRaft().receiveVote(), "A single-node cluster must win its own election");
            node.getRaft().becomeLeader();
            int index = node.getRaft().appendEntry("durable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1, index);
            assertEquals(1, node.getRaft().getLastLogIndex());
            assertEquals(1, node.getRaft().getLastLogTerm());
            assertTrue(Files.exists(nodeStorage.resolve("raft-log.bin")),
                    "The raft log file must exist after an append");
        } finally {
            node.stop();
        }

        // A fresh process on the same storage directory replays the log.
        ClusterNode restarted = new ClusterNode("node-c", 9099, directory(9099),
                100, 500, electionTimeout, 150, null, security, nodeStorage);
        restarted.start();
        try {
            assertEquals(1, restarted.getRaft().getLastLogIndex(), "Restarted node must replay its raft log");
            assertEquals(1, restarted.getRaft().getLastLogTerm(), "Restarted node must restore the entry's term");
        } finally {
            restarted.stop();
        }
    }
}
