package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.ReadIndexRequest;
import com.minigoogle.cluster.transport.dto.ReadIndexResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.minigoogle.storage.metadata.RaftMetadata;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Raft election metadata durability: votes and terms are persisted before
 * they are acknowledged, and a restart on the same store never re-issues a
 * vote or regresses the term.
 */
class RaftConsensusPersistenceTest {

    private RaftMetadataStore fileStore(String name) throws IOException {
        Path dir = Files.createTempDirectory("raft-persist-" + name);
        dir.toFile().deleteOnExit();
        return new RaftMetadataStore(dir.resolve(name + "-raft-metadata.bin"));
    }

    @Test
    void testVoteGrantIsPersistedBeforeReply() throws Exception {
        RaftMetadataStore store = fileStore("voter");
        RaftConsensus voter = new RaftConsensus("voter", 300, 100, 3, null, null, store);

        assertTrue(voter.receiveVoteRequest("candidate-a", 5));

        RaftMetadata persisted = store.load();
        assertEquals(5, persisted.currentTerm());
        assertEquals("candidate-a", persisted.votedFor());
    }

    @Test
    void testHigherTermGrantPersistsNewTermAndCandidate() throws Exception {
        RaftMetadataStore store = fileStore("voter");
        RaftConsensus voter = new RaftConsensus("voter", 300, 100, 3, null, null, store);

        assertTrue(voter.receiveVoteRequest("candidate-a", 2));
        assertTrue(voter.receiveVoteRequest("candidate-c", 3));

        RaftMetadata persisted = store.load();
        assertEquals(3, persisted.currentTerm());
        assertEquals("candidate-c", persisted.votedFor());
    }

    @Test
    void testRestartRestoresTermAndVote() throws Exception {
        RaftMetadataStore store = fileStore("voter");
        RaftConsensus voter = new RaftConsensus("voter", 300, 100, 3, null, null, store);
        assertTrue(voter.receiveVoteRequest("candidate-a", 5));

        RaftConsensus restarted = new RaftConsensus("voter", 300, 100, 3, null, null, store);
        assertEquals(5, restarted.getCurrentTerm());
        // A different candidate in the same term must be denied — no double vote.
        assertFalse(restarted.receiveVoteRequest("candidate-b", 5));
        // The same candidate in the same term is re-granted (the identical vote).
        assertTrue(restarted.receiveVoteRequest("candidate-a", 5));
    }

    @Test
    void testRestartBlocksStaleTerm() throws Exception {
        RaftMetadataStore store = fileStore("voter");
        RaftConsensus voter = new RaftConsensus("voter", 300, 100, 3, null, null, store);
        assertTrue(voter.receiveVoteRequest("candidate-a", 7));

        RaftConsensus restarted = new RaftConsensus("voter", 300, 100, 3, null, null, store);
        assertEquals(7, restarted.getCurrentTerm());
        // A candidate campaigning on a stale term must never win the vote.
        assertFalse(restarted.receiveVoteRequest("candidate-b", 3));
        // And the node must not accept a stale heartbeat as authoritative.
        restarted.receiveHeartbeat("stale-leader", 3);
        assertEquals(7, restarted.getCurrentTerm());
    }

    @Test
    void testElectionPersistsTermAndSelfVoteBeforeRpc() throws Exception {
        RaftMetadataStore store = fileStore("candidate");
        RaftConsensus candidate = new RaftConsensus("candidate", 300, 100, 3, null, null, store);

        candidate.startElection();

        RaftMetadata persisted = store.load();
        assertEquals(1, persisted.currentTerm());
        assertEquals("candidate", persisted.votedFor());
        assertEquals(1, candidate.getCurrentTerm());
    }

    @Test
    void testHigherTermHeartbeatPersistsTerm() throws Exception {
        RaftMetadataStore store = fileStore("follower");
        RaftConsensus follower = new RaftConsensus("follower", 300, 100, 3, null, null, store);

        follower.receiveHeartbeat("leader", 4);

        RaftMetadata persisted = store.load();
        assertEquals(4, persisted.currentTerm());
        assertNull(persisted.votedFor());
        assertEquals(4, follower.getCurrentTerm());
    }

    @Test
    void testSameTermHeartbeatDoesNotRewriteMetadata() throws Exception {
        RaftMetadataStore store = fileStore("follower");
        RaftConsensus follower = new RaftConsensus("follower", 300, 100, 3, null, null, store);
        follower.receiveVoteRequest("candidate-a", 2);

        long lastModified = store.load().currentTerm();
        assertEquals(2, lastModified);
        follower.receiveHeartbeat("leader", 2);
        assertEquals(2, follower.getCurrentTerm());
        // The vote must not have been cleared by a same-term heartbeat.
        RaftMetadata persisted = store.load();
        assertEquals(2, persisted.currentTerm());
        assertEquals("candidate-a", persisted.votedFor());
    }

    @Test
    void testStepDownPersistsNewTermAndClearsVote() throws Exception {
        RaftMetadataStore store = fileStore("leader");
        RaftTransport transport = new RaftTransport() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
                return CompletableFuture.completedFuture(new RequestVoteResponse(
                        ClusterProtocol.PROTOCOL_VERSION, request.requestId(), request.correlationId(),
                        "follower", System.currentTimeMillis(), 1, false));
            }

            @Override
            public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request) {
                return CompletableFuture.completedFuture(new AppendEntriesResponse(
                        ClusterProtocol.PROTOCOL_VERSION, request.requestId(), request.correlationId(),
                        "follower", System.currentTimeMillis(), 9, true));
            }

            @Override
            public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeId, InstallSnapshotRequest request) {
                return CompletableFuture.completedFuture(new InstallSnapshotResponse(
                        ClusterProtocol.PROTOCOL_VERSION, request.requestId(), request.correlationId(),
                        "follower", System.currentTimeMillis(), 1, true));
            }

            @Override
            public CompletableFuture<ReadIndexResponse> sendReadIndex(String targetNodeId, ReadIndexRequest request) {
                return CompletableFuture.completedFuture(new ReadIndexResponse(
                        ClusterProtocol.PROTOCOL_VERSION, request.requestId(), request.correlationId(),
                        "follower", System.currentTimeMillis(), 1, 0, true));
            }
        };

        RaftConsensus leader = new RaftConsensus("leader", 300, 100, 2, transport,
                () -> List.of("leader", "follower"), store);
        leader.startElection();
        assertTrue(leader.receiveVote(), "Majority of 2 should win the election");
        leader.becomeLeader();
        assertEquals(1, leader.getCurrentTerm());

        // The follower answers with a higher term — the leader steps down and
        // must persist the new term and clear its vote before the response
        // completes.
        leader.sendHeartbeats();

        assertEquals(9, leader.getCurrentTerm());
        assertEquals(RaftConsensus.RaftState.FOLLOWER, leader.getState());
        RaftMetadata persisted = store.load();
        assertEquals(9, persisted.currentTerm());
        assertNull(persisted.votedFor());
    }

    @Test
    void testDeniedVoteDoesNotMutateMetadata() throws Exception {
        RaftMetadataStore store = fileStore("voter");
        RaftConsensus voter = new RaftConsensus("voter", 300, 100, 3, null, null, store);
        assertTrue(voter.receiveVoteRequest("candidate-a", 5));

        assertFalse(voter.receiveVoteRequest("candidate-b", 5));

        RaftMetadata persisted = store.load();
        assertEquals(5, persisted.currentTerm());
        assertEquals("candidate-a", persisted.votedFor());
    }
}
