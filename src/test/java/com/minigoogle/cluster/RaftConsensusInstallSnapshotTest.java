package com.minigoogle.cluster;

import com.minigoogle.cluster.state.KvCommand;
import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;
import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;

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
 * InstallSnapshot RPC: a follower whose log has been consumed by the leader's
 * snapshot adopts it (restoring the state machine and re-basing the log), a
 * stale snapshot is ignored, a higher term reverts the node to follower, a
 * non-snapshotable state machine refuses, and a leader whose follower falls
 * below its first retained index switches from AppendEntries to
 * InstallSnapshot and back.
 */
class RaftConsensusInstallSnapshotTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    private static final byte[] V1 = "v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V2 = "v2".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V3 = "v3".getBytes(StandardCharsets.UTF_8);

    @Test
    void testFollowerInstallsSnapshotRestoringState() {
        ReplicatedKeyValueStore followerStore = new ReplicatedKeyValueStore();
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory(), followerStore, null);

        // An old leader (term 1) appends two entries that never commit.
        assertTrue(follower.receiveAppendEntries("old-leader", 1, 0, 0,
                List.of(RaftLog.toFrame(1, KvCommand.encodePut("stale", V1)),
                        RaftLog.toFrame(1, KvCommand.encodePut("stale2", V2))),
                0));
        assertEquals(2, follower.getLastLogIndex());
        assertEquals(0, follower.getLastApplied());

        byte[] data = snapshotOf(
                KvCommand.encodePut("k", V1),
                KvCommand.encodePut("j", V2));

        assertTrue(follower.receiveInstallSnapshot("leader", 5, 10, 4, data));

        assertEquals(10, follower.getLastApplied(), "The watermark must jump to the snapshot");
        assertEquals(5, follower.getCurrentTerm(), "A higher term must be adopted");
        assertEquals(RaftConsensus.RaftState.FOLLOWER, follower.getState());
        assertEquals(11, follower.getLogFirstIndex(), "The log must be re-based at the snapshot");
        assertEquals(10, follower.getLastLogIndex());
        assertArrayEquals(V1, followerStore.get("k"));
        assertArrayEquals(V2, followerStore.get("j"));
        assertNull(followerStore.get("stale"), "The pre-snapshot state must be replaced");
    }

    @Test
    void testInstallIsIdempotentForAnUpToDateFollower() {
        ReplicatedKeyValueStore followerStore = new ReplicatedKeyValueStore();
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory(), followerStore, null);

        byte[] data = snapshotOf(KvCommand.encodePut("k", V1));
        assertTrue(follower.receiveInstallSnapshot("leader", 2, 5, 2, data));
        assertEquals(5, follower.getLastApplied());
        assertArrayEquals(V1, followerStore.get("k"));

        // A repeat of the same snapshot (already applied) must be a no-op.
        assertTrue(follower.receiveInstallSnapshot("leader", 2, 5, 2, data));
        assertEquals(5, follower.getLastApplied());
        assertArrayEquals(V1, followerStore.get("k"));
    }

    @Test
    void testStaleSnapshotIsIgnored() {
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory());
        follower.receiveHeartbeat("leader", 10);
        assertEquals(10, follower.getCurrentTerm());

        byte[] data = snapshotOf(KvCommand.encodePut("k", V1));
        assertFalse(follower.receiveInstallSnapshot("leader", 5, 3, 2, data),
                "A snapshot from a lower term must be rejected");
        assertEquals(10, follower.getCurrentTerm());
        assertEquals(0, follower.getLastApplied());
    }

    @Test
    void testHigherTermInstallsAndStepsDownFromCandidate() {
        ReplicatedKeyValueStore followerStore = new ReplicatedKeyValueStore();
        RaftConsensus candidate = new RaftConsensus("candidate", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory(), followerStore, null);
        candidate.startElection();
        assertEquals(RaftConsensus.RaftState.CANDIDATE, candidate.getState());

        byte[] data = snapshotOf(KvCommand.encodePut("k", V1));
        assertTrue(candidate.receiveInstallSnapshot("leader", 2, 4, 1, data));

        assertEquals(RaftConsensus.RaftState.FOLLOWER, candidate.getState(),
                "A candidate must step down to follower");
        assertEquals(2, candidate.getCurrentTerm());
        assertEquals(4, candidate.getLastApplied());
        assertArrayEquals(V1, followerStore.get("k"));
    }

    @Test
    void testNonSnapshotableStateMachineRefusesToInstall() {
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), RaftLog.inMemory(), new EchoStateMachine(), null);

        byte[] data = snapshotOf(KvCommand.encodePut("k", V1));
        assertFalse(follower.receiveInstallSnapshot("leader", 1, 5, 1, data),
                "A state machine that cannot restore must refuse the snapshot");
        assertEquals(0, follower.getLastApplied());
        assertEquals(0, follower.getLastLogIndex());
    }

    @Test
    void testLeaderRecoversFarBehindFollowerViaInstallSnapshot() throws Exception {
        Path dir = Files.createTempDirectory("raft-install-fallback");
        dir.toFile().deleteOnExit();

        RaftLog leaderLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        RaftSnapshotStore snapshotStore = new RaftSnapshotStore(dir.resolve("raft-snapshot.bin"));
        ReplicatedKeyValueStore leaderStore = new ReplicatedKeyValueStore();
        ReplicatedKeyValueStore behindStore = new ReplicatedKeyValueStore();

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("a", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), leaderLog,
                leaderStore, null, snapshotStore, 1);
        RaftConsensus behind = new RaftConsensus("b", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                behindStore, null);
        RaftConsensus online = new RaftConsensus("c", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(behind);
        transport.register(online);
        transport.goSilent("b");

        try {
            makeLeader(leader);
            for (int i = 1; i <= 5; i++) {
                int target = i;
                leader.appendEntry(KvCommand.encodePut("k" + i, value(i)));
                // Commit and compact each entry before the next append, so the
                // interval-1 snapshot lands deterministically at every applied
                // index (base 5, firstIndex 6) instead of a race-sized batch.
                assertTrue(pumpUntil(leader, () -> leader.getLastApplied() == target
                        && leader.getLogFirstIndex() == target + 1, 3000),
                        "Entry " + i + " must commit before the next append");
            }
            // "c" keeps the majority alive while "b" falls further behind.
            assertEquals(6, leader.getLogFirstIndex(), "Interval 1 must compact the whole prefix");

            // Once "b" is reachable again, the leader must hand it the snapshot.
            transport.unSilent("b");
            assertTrue(pumpUntil(leader, () -> behind.getLastApplied() == 5, 3000),
                    "The behind follower must catch up via InstallSnapshot");

            assertEquals(5, behind.getLastApplied());
            assertEquals(6, behind.getLogFirstIndex(), "The follower's log must re-base at the snapshot");
            for (int i = 1; i <= 5; i++) {
                assertArrayEquals(value(i), behindStore.get("k" + i));
            }
            assertArrayEquals(value(5), leaderStore.get("k5"));
        } finally {
            leader.stop();
            behind.stop();
            online.stop();
            transport.shutdown();
        }
    }

    @Test
    void testLeaderStepsDownWhenInstallReplyCarriesHigherTerm() throws Exception {
        Path dir = Files.createTempDirectory("raft-install-stepdown");
        dir.toFile().deleteOnExit();

        RaftLog leaderLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        RaftSnapshotStore snapshotStore = new RaftSnapshotStore(dir.resolve("raft-snapshot.bin"));

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("a", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), leaderLog,
                new ReplicatedKeyValueStore(), null, snapshotStore, 1);
        RaftConsensus behind = new RaftConsensus("b", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        RaftConsensus online = new RaftConsensus("c", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(behind);
        transport.register(online);
        transport.goSilent("b");

        try {
            makeLeader(leader);
            for (int i = 1; i <= 5; i++) {
                int target = i;
                leader.appendEntry(KvCommand.encodePut("k" + i, value(i)));
                assertTrue(pumpUntil(leader, () -> leader.getLastApplied() == target
                        && leader.getLogFirstIndex() == target + 1, 3000),
                        "Entry " + i + " must commit before the next append");
            }
            assertEquals(RaftConsensus.RaftState.LEADER, leader.getState());

            // The follower replies to the install with an unreachable term.
            transport.forceInstallTerm(9);
            transport.unSilent("b");
            assertTrue(pumpUntil(leader, () -> leader.getState() != RaftConsensus.RaftState.LEADER, 3000),
                    "The higher term in the install reply must step the leader down");
            assertEquals(RaftConsensus.RaftState.FOLLOWER, leader.getState());
            assertEquals(9, leader.getCurrentTerm());
        } finally {
            leader.stop();
            behind.stop();
            online.stop();
            transport.shutdown();
        }
    }

    /**
     * Builds the snapshot bytes of a state machine holding the given puts.
     */
    private static byte[] snapshotOf(byte[]... puts) {
        ReplicatedKeyValueStore source = new ReplicatedKeyValueStore();
        for (int i = 0; i < puts.length; i++) {
            source.apply(new LogEntry(i + 1, 1, puts[i]));
        }
        return source.snapshot();
    }

    private static byte[] value(int i) {
        return ("v" + i).getBytes(StandardCharsets.UTF_8);
    }

    private void makeLeader(RaftConsensus node) {
        node.startElection();
        assertTrue(node.receiveVote(), "Majority must win the election");
        node.becomeLeader();
        assertEquals(RaftConsensus.RaftState.LEADER, node.getState());
    }

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

    private static final class EchoStateMachine implements StateMachine {
        @Override
        public void apply(LogEntry entry) {
        }
    }

    /**
     * An in-memory RaftTransport that routes full-field RPCs into the target
     * RaftConsensus nodes, mirroring how RaftHandler dispatches over HTTP.
     */
    private static class FakeRaftTransport implements RaftTransport {
        private final Map<String, RaftConsensus> nodes = new ConcurrentHashMap<>();
        private final Set<String> silent = new CopyOnWriteArraySet<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private volatile int forcedInstallTerm;

        void register(RaftConsensus node) {
            nodes.put(node.getNodeId(), node);
        }

        void goSilent(String nodeId) {
            silent.add(nodeId);
        }

        void unSilent(String nodeId) {
            silent.remove(nodeId);
        }

        void forceInstallTerm(int term) {
            forcedInstallTerm = term;
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
                RaftConsensus target = nodes.get(targetNodeId);
                boolean success = false;
                int term = request.term();
                if (target != null && !silent.contains(targetNodeId)) {
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
                RaftConsensus target = nodes.get(targetNodeId);
                boolean success = false;
                int term = request.term();
                if (target != null && !silent.contains(targetNodeId)) {
                    success = target.receiveInstallSnapshot(request.leaderId(), request.term(),
                            request.lastIncludedIndex(), request.lastIncludedTerm(), request.data());
                    term = target.getCurrentTerm();
                }
                if (forcedInstallTerm > 0) {
                    term = forcedInstallTerm;
                }
                return new InstallSnapshotResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(), term, success);
            }, executor);
        }
    }
}
