package com.minigoogle.cluster;

import com.minigoogle.cluster.state.KvCommand;
import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * State-machine apply over the fake transport: entries must be applied exactly
 * once, in index order, on the leader and on followers, only after commit; a
 * truncated uncommitted tail must never be applied; and the applied watermark
 * must survive a restart so the state machine can be rebuilt from the durable
 * log without waiting for the next commit.
 */
class RaftConsensusApplyTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    private static final byte[] V1 = "v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V2 = "v2".getBytes(StandardCharsets.UTF_8);

    @Test
    void testLeaderAndFollowerApplyCommittedEntryOnce() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        ReplicatedKeyValueStore leaderStore = new ReplicatedKeyValueStore();
        ReplicatedKeyValueStore followerStore = new ReplicatedKeyValueStore();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                leaderStore, null);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                followerStore, null);
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);

            leader.appendEntry(KvCommand.encodePut("k", V1));
            leader.appendEntry(KvCommand.encodePut("j", V2));

            assertTrue(waitUntil(() -> leader.getCommitIndex() == 2, 3000), "Leader must commit both entries");
            assertTrue(pumpUntil(leader, () -> follower.getCommitIndex() == 2 && follower.getLastApplied() == 2, 3000),
                    "Follower must observe both commits and apply them");

            assertEquals(2, leader.getLastApplied());
            assertEquals(2, follower.getLastApplied());
            assertArrayEquals(V1, leaderStore.get("k"));
            assertArrayEquals(V2, leaderStore.get("j"));
            assertArrayEquals(V1, followerStore.get("k"));
            assertArrayEquals(V2, followerStore.get("j"));
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testFollowerAppliesOnceEvenAfterRepeatedHeartbeats() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        ReplicatedKeyValueStore followerStore = new ReplicatedKeyValueStore();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                followerStore, null);
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            leader.appendEntry(KvCommand.encodePut("k", V1));
            assertTrue(pumpUntil(leader, () -> follower.getLastApplied() == 1, 3000),
                    "Follower must apply the committed entry");

            // Repeated heartbeat rounds must not re-apply the entry.
            for (int i = 0; i < 5; i++) {
                leader.sendHeartbeats();
            }
            assertEquals(1, follower.getLastApplied(), "Applied exactly once");
            assertArrayEquals(V1, followerStore.get("k"));
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testUncommittedEntriesAreNeverApplied() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        ReplicatedKeyValueStore leaderStore = new ReplicatedKeyValueStore();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                leaderStore, null);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(follower);
        transport.goSilent("follower");

        try {
            makeLeader(leader);
            leader.appendEntry(KvCommand.encodePut("k", V1));
            leader.appendEntry(KvCommand.encodePut("j", V2));

            Thread.sleep(300);
            assertEquals(0, leader.getCommitIndex(), "No majority, nothing commits");
            assertEquals(0, leader.getLastApplied(), "Uncommitted entries must not be applied");
            assertNull(leaderStore.get("k"));
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testTruncatedUncommittedTailIsNeverApplied() {
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();
        RaftConsensus follower = new RaftConsensus("f", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory(), store, null);

        // An old leader (term 1) appends two entries but never commits them.
        assertTrue(follower.receiveAppendEntries("old-leader", 1, 0, 0,
                List.of(RaftLog.toFrame(1, KvCommand.encodePut("k", V1)), RaftLog.toFrame(1, KvCommand.encodePut("j", V2))),
                0));
        assertEquals(2, follower.getLastLogIndex());
        assertEquals(0, follower.getLastApplied(), "Uncommitted tail must not be applied");

        // A new leader (term 2) overwrites index 2 and commits up to 2.
        assertTrue(follower.receiveAppendEntries("new-leader", 2, 1, 1,
                List.of(RaftLog.toFrame(2, KvCommand.encodePut("k", V2))), 2));
        assertEquals(2, follower.getCommitIndex());
        assertEquals(2, follower.getLastApplied());
        assertArrayEquals(V2, store.get("k"), "Only the committed value must be applied");
        assertNull(store.get("j"), "The truncated entry must never have been applied");
    }

    @Test
    void testNullStateMachineKeepsPhase3Behavior() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory());
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory());
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            leader.appendEntry(KvCommand.encodePut("k", V1));
            assertTrue(waitUntil(() -> leader.getCommitIndex() == 1, 3000));
            assertEquals(1, leader.getCommitIndex());
            assertEquals(0, leader.getLastApplied(), "No state machine: watermark stays 0");
            assertEquals(0, follower.getLastApplied());
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testAppliedWatermarkRebuildsStateOnRestart() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("raft-apply-restart");
        dir.toFile().deleteOnExit();

        WriteAheadLog wal = new WriteAheadLog(dir.resolve("raft-log.bin"));
        RaftLog durableLog = new RaftLog(wal);
        RaftAppliedStore appliedStore = new RaftAppliedStore(dir.resolve("raft-applied.bin"));
        ReplicatedKeyValueStore store = new ReplicatedKeyValueStore();

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), durableLog, store, appliedStore);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            leader.appendEntry(KvCommand.encodePut("k", V1));
            leader.appendEntry(KvCommand.encodeDelete("missing"));
            assertTrue(waitUntil(() -> leader.getCommitIndex() == 2, 3000));
            assertTrue(waitUntil(() -> leader.getLastApplied() == 2, 3000));
            assertTrue(waitUntil(() -> {
                        try {
                            return appliedStore.load() == 2;
                        } catch (IOException e) {
                            return false;
                        }
                    }, 3000),
                    "The watermark must be durable before the restart");
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }

        // "Restart": a fresh store on the same durable log + watermark must
        // rebuild the state machine without any replication.
        RaftLog replayed = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        ReplicatedKeyValueStore rebuilt = new ReplicatedKeyValueStore();
        RaftConsensus restarted = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), replayed, rebuilt, appliedStore);

        assertEquals(2, restarted.getLastApplied());
        assertArrayEquals(V1, rebuilt.get("k"));
        assertEquals(2, replayed.lastIndex());
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
     * tests, so followers learn a higher {@code leaderCommit} only when the
     * leader sends another round.
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
     * A node put into silence answers success=false so the leader can never
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
    }
}
