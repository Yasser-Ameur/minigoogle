package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Raft log replication over a shared fake transport. The fake routes the full
 * AppendEntries and RequestVote fields exactly as the HTTP handler does, so
 * these tests exercise the real consensus replication pipeline: append,
 * replicate, majority commit, log-consistency rejection, conflict truncation,
 * and the vote up-to-date check.
 */
class RaftConsensusReplicationTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    private static final byte[] A = "a".getBytes(StandardCharsets.UTF_8);
    private static final byte[] B = "b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] C = "c".getBytes(StandardCharsets.UTF_8);

    @Test
    void testAppendEntryReplicatesToFollowerAndCommits() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);

            int index1 = leader.appendEntry(A);
            int index2 = leader.appendEntry(B);
            assertEquals(1, index1);
            assertEquals(2, index2);

            assertTrue(waitUntil(() -> follower.getLastLogIndex() == 2, 3000),
                    "The follower must replicate both entries");
            assertEquals(leader.getLastLogIndex(), follower.getLastLogIndex());
            assertEquals(leader.getLastLogTerm(), follower.getLastLogTerm());
            assertTrue(waitUntil(() -> leader.getCommitIndex() == 2, 3000),
                    "The leader must commit entries replicated to a majority");
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testNoCommitWithoutMajorityThenCommitAfterRecovery() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);
        transport.goSilent("follower");

        try {
            makeLeader(leader);
            leader.appendEntry(A);

            Thread.sleep(300);
            assertEquals(0, leader.getCommitIndex(), "A single-node majority must not commit");
            assertEquals(0, follower.getLastLogIndex(), "A silent follower replicates nothing");

            transport.unSilent("follower");
            leader.sendHeartbeats();

            assertTrue(waitUntil(() -> leader.getCommitIndex() == 1, 3000),
                    "The leader must commit once the follower recovers");
            assertEquals(1, follower.getLastLogIndex());
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testCommitWithMajorityOfThree() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("leader", "f1", "f2"));
        RaftConsensus f1 = new RaftConsensus("f1", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("leader", "f1", "f2"));
        RaftConsensus f2 = new RaftConsensus("f2", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("leader", "f1", "f2"));
        transport.register(leader);
        transport.register(f1);
        transport.register(f2);
        transport.goSilent("f2");

        try {
            makeLeader(leader);
            leader.appendEntry(A);

            assertTrue(waitUntil(() -> f1.getLastLogIndex() == 1, 3000),
                    "The live follower must replicate the entry");
            assertTrue(waitUntil(() -> leader.getCommitIndex() == 1, 3000),
                    "Leader + one follower is a majority of 3; the entry must commit");
            assertEquals(0, f2.getLastLogIndex(), "The silent follower must not have the entry");
        } finally {
            leader.stop();
            f1.stop();
            f2.stop();
            transport.shutdown();
        }
    }

    @Test
    void testAppendEntriesRejectedOnMismatchedPrevLog() {
        RaftLog log = RaftLog.inMemory();
        log.append(1, A);
        RaftConsensus follower = new RaftConsensus("f", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), log);

        // prevLogTerm 5 does not match termAt(1) == 1: must be rejected.
        assertFalse(follower.receiveAppendEntries("leader", 2, 1, 5, List.of(RaftLog.toFrame(2, B)), 0));

        // A correct prev index/term pair is accepted and the entry appended.
        assertTrue(follower.receiveAppendEntries("leader", 2, 1, 1, List.of(RaftLog.toFrame(2, B)), 0));
        assertEquals(2, follower.getLastLogIndex());
        assertEquals(2, follower.getLastLogTerm());

        // A prevLogIndex past the end of the log must be rejected.
        assertFalse(follower.receiveAppendEntries("leader", 2, 9, 2, List.of(RaftLog.toFrame(2, C)), 0));
    }

    @Test
    void testFollowerTruncatesConflictingTail() {
        RaftConsensus follower = new RaftConsensus("f", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory());

        assertTrue(follower.receiveAppendEntries("leader", 1, 0, 0,
                List.of(RaftLog.toFrame(1, A), RaftLog.toFrame(2, B)), 0));
        assertEquals(2, follower.getLastLogIndex());
        assertEquals(2, follower.getLastLogTerm());

        // A new leader claims index 2 carries term 3; the divergent tail must be
        // truncated and replaced.
        assertTrue(follower.receiveAppendEntries("leader", 3, 1, 1, List.of(RaftLog.toFrame(3, C)), 0));
        assertEquals(2, follower.getLastLogIndex());
        assertEquals(3, follower.getLastLogTerm());
        assertEquals(3, follower.getCurrentTerm());
    }

    @Test
    void testVoteDeniedToStaleCandidate() {
        RaftLog log = RaftLog.inMemory();
        log.append(1, A);
        log.append(1, B);
        RaftConsensus voter = new RaftConsensus("voter", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), log);

        // Empty-log candidate: behind (term 0 < 1) -> denied.
        assertFalse(voter.receiveVoteRequest("stale-empty", 2, 0, 0));

        // Same term but shorter index -> denied.
        assertFalse(voter.receiveVoteRequest("stale-short", 2, 1, 1));

        // Same term, equal index -> granted.
        assertTrue(voter.receiveVoteRequest("equal", 2, 2, 1));

        // New term -> granted.
        assertTrue(voter.receiveVoteRequest("ahead", 3, 2, 1));
        assertEquals("ahead", voter.getVotedFor());
    }

    @Test
    void testLeaderBacksOffAndConverges() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("leader", "follower", "late"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("leader", "follower", "late"));
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            leader.appendEntry(A);
            leader.appendEntry(B);
            leader.appendEntry(C);
            assertTrue(waitUntil(() -> leader.getCommitIndex() == 3, 3000),
                    "The leader must commit all three entries");

            // A second follower joins with a log one entry behind: the leader must
            // reject via backoff and converge it onto the full log.
            RaftLog shortLog = RaftLog.inMemory();
            shortLog.append(1, A);
            RaftConsensus lateJoiner = new RaftConsensus("late", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                    transport, () -> List.of("leader", "late"), RaftMetadataStore.inMemory(), shortLog);
            transport.register(lateJoiner);

            assertTrue(pumpUntil(leader, () -> lateJoiner.getLastLogIndex() == 3, 5000),
                    "The leader must back off and replicate the full log to the lagging follower");
            assertEquals(1, lateJoiner.getLastLogTerm());
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    private void makeLeader(RaftConsensus node) {
        node.startElection();
        assertTrue(node.receiveVote(), "Majority must win the election");
        node.becomeLeader();
        assertEquals(RaftConsensus.RaftState.LEADER, node.getState());
    }

    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /**
     * Repeatedly drives heartbeat rounds on the leader until the condition is
     * met. Needed because the leader's scheduler is not started in these
     * tests, so replication advances only when {@code sendHeartbeats} is
     * called explicitly (one round may only back off the follower's nextIndex).
     */
    private boolean pumpUntil(RaftConsensus leader, BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            leader.sendHeartbeats();
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /**
     * An in-memory RaftTransport that routes full-field RPCs into the target
     * RaftConsensus nodes, mirroring how RaftHandler dispatches over HTTP.
     * Delivery is asynchronous (virtual threads) like the real HTTP client.
     * A node put into silence answers success=false, so the leader can never
     * count it toward a commit.
     */
    private static class FakeRaftTransport implements RaftTransport {
        private final Map<String, RaftConsensus> nodes = new ConcurrentHashMap<>();
        private final Set<String> silent = new CopyOnWriteArraySet<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        void register(RaftConsensus node) {
            nodes.put(node.getNodeId(), node);
        }

        void goSilent(String nodeId) {
            silent.add(nodeId);
        }

        void unSilent(String nodeId) {
            silent.remove(nodeId);
        }

        void shutdown() {
            executor.shutdown();
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                RaftConsensus target = nodes.get(targetNodeId);
                if (target == null) {
                    return new RequestVoteResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                            request.correlationId(), targetNodeId, System.currentTimeMillis(), request.term(), false);
                }
                boolean granted = target.receiveVoteRequest(request.candidateId(), request.term(),
                        request.lastLogIndex(), request.lastLogTerm());
                return new RequestVoteResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(),
                        target.getCurrentTerm(), granted);
            }, executor);
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                if (silent.contains(targetNodeId)) {
                    return new AppendEntriesResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                            request.correlationId(), targetNodeId, System.currentTimeMillis(),
                            request.term(), false);
                }
                RaftConsensus target = nodes.get(targetNodeId);
                boolean success = false;
                int term = request.term();
                if (target != null) {
                    success = target.receiveAppendEntries(request.leaderId(), request.term(),
                            request.prevLogIndex(), request.prevLogTerm(), request.entries(), request.leaderCommit());
                    term = target.getCurrentTerm();
                }
                return new AppendEntriesResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(), term, success);
            }, executor);
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeId, InstallSnapshotRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                if (silent.contains(targetNodeId)) {
                    return new InstallSnapshotResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                            request.correlationId(), targetNodeId, System.currentTimeMillis(),
                            request.term(), false);
                }
                RaftConsensus target = nodes.get(targetNodeId);
                boolean success = false;
                int term = request.term();
                if (target != null) {
                    success = target.receiveInstallSnapshot(request.leaderId(), request.term(),
                            request.lastIncludedIndex(), request.lastIncludedTerm(), request.data());
                    term = target.getCurrentTerm();
                }
                return new InstallSnapshotResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(), term, success);
            }, executor);
        }
    }
}
