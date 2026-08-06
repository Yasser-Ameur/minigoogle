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
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot-driven log compaction at the consensus layer: once {@code
 * snapshotInterval} committed entries have been applied, the leader persists a
 * durable snapshot and compacts the log prefix; non-snapshotable state
 * machines and missing snapshot stores never compact; and a restart rebuilds
 * the state machine from the snapshot plus the compacted tail.
 */
class RaftConsensusSnapshotTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    @Test
    void testSnapshotCompactsLogPrefixOnceIntervalReached() throws Exception {
        Path dir = Files.createTempDirectory("raft-snapshot-compact");
        dir.toFile().deleteOnExit();

        RaftLog leaderLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        RaftSnapshotStore snapshotStore = new RaftSnapshotStore(dir.resolve("raft-snapshot.bin"));
        RaftAppliedStore appliedStore = new RaftAppliedStore(dir.resolve("raft-applied.bin"));
        ReplicatedKeyValueStore leaderStore = new ReplicatedKeyValueStore();

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), leaderLog,
                leaderStore, appliedStore, snapshotStore, 3);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            for (int i = 1; i <= 4; i++) {
                int target = i;
                leader.appendEntry(KvCommand.encodePut("k" + i, value(i)));
                // Commit each entry before the next append so commits are
                // single-entry and the interval-3 snapshot lands exactly at
                // applied index 3 (a batch commit would shift it to 4).
                assertTrue(pumpUntil(leader, () -> leader.getLastApplied() == target, 3000),
                        "Entry " + i + " must apply before the next append");
            }
            assertTrue(pumpUntil(leader, () -> follower.getLastApplied() == 4, 3000),
                    "Follower must catch up after the compaction");

            RaftSnapshot snapshot = snapshotStore.load();
            assertNotNull(snapshot, "A snapshot must exist once the interval is reached");
            assertEquals(3, snapshot.lastIncludedIndex());
            assertEquals(4, leader.getLogFirstIndex(), "The log must be re-based at the snapshot");
            assertEquals(4, leader.getLastLogIndex());
            for (int i = 1; i <= 4; i++) {
                assertArrayEquals(value(i), leaderStore.get("k" + i));
            }
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testNonSnapshotableStateMachineNeverCompacts() throws Exception {
        Path dir = Files.createTempDirectory("raft-snapshot-echo");
        dir.toFile().deleteOnExit();

        RaftLog leaderLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        RaftSnapshotStore snapshotStore = new RaftSnapshotStore(dir.resolve("raft-snapshot.bin"));
        EchoStateMachine stateMachine = new EchoStateMachine();

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), leaderLog,
                stateMachine, null, snapshotStore, 1);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory());
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            for (int i = 1; i <= 3; i++) {
                leader.appendEntry(KvCommand.encodePut("k" + i, value(i)));
            }
            assertTrue(pumpUntil(leader, () -> leader.getLastApplied() == 3, 3000),
                    "Entries must be applied even though the state machine cannot snapshot");
            assertNull(snapshotStore.load(), "A non-snapshotable state machine must never be snapshotted");
            assertEquals(1, leader.getLogFirstIndex(), "The log must never be compacted");
            assertEquals(3, stateMachine.applied.get());
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testNoSnapshotStoreMeansNoCompaction() throws Exception {
        Path dir = Files.createTempDirectory("raft-snapshot-no-store");
        dir.toFile().deleteOnExit();

        RaftLog leaderLog = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")));
        ReplicatedKeyValueStore leaderStore = new ReplicatedKeyValueStore();

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), leaderLog,
                leaderStore, null, null, 1);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            for (int i = 1; i <= 3; i++) {
                leader.appendEntry(KvCommand.encodePut("k" + i, value(i)));
            }
            assertTrue(pumpUntil(leader, () -> leader.getLastApplied() == 3, 3000));
            assertEquals(1, leader.getLogFirstIndex(),
                    "Without a snapshot store the log must never be compacted");
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testRestartRebuildsStateFromSnapshotAndCompactedTail() throws Exception {
        Path dir = Files.createTempDirectory("raft-snapshot-restart");
        dir.toFile().deleteOnExit();

        WriteAheadLog wal = new WriteAheadLog(dir.resolve("raft-log.bin"));
        RaftLog leaderLog = new RaftLog(wal);
        RaftSnapshotStore snapshotStore = new RaftSnapshotStore(dir.resolve("raft-snapshot.bin"));
        RaftAppliedStore appliedStore = new RaftAppliedStore(dir.resolve("raft-applied.bin"));
        ReplicatedKeyValueStore leaderStore = new ReplicatedKeyValueStore();

        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), leaderLog,
                leaderStore, appliedStore, snapshotStore, 2);
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                new ReplicatedKeyValueStore(), null);
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            for (int i = 1; i <= 5; i++) {
                int target = i;
                leader.appendEntry(KvCommand.encodePut("k" + i, value(i)));
                // Commit each entry before the next append, so the interval
                // boundary lands exactly on applied indexes 2 and 4.
                assertTrue(pumpUntil(leader, () -> leader.getLastApplied() == target, 3000),
                        "Entry " + i + " must apply before the next append");
            }
            assertTrue(waitUntil(() -> {
                        try {
                            return appliedStore.load() == 5;
                        } catch (IOException e) {
                            return false;
                        }
                    }, 3000),
                    "The applied watermark must be durable before the restart");
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }

        // Interval 2 with single-entry commits: snapshots at applied indexes 2
        // and 4, so the final base is 4 and only the entry at index 5 survives
        // in the WAL.
        RaftSnapshot snapshot = snapshotStore.load();
        assertNotNull(snapshot);
        assertEquals(4, snapshot.lastIncludedIndex());

        // "Restart": the compacted WAL tail is only interpretable with the
        // snapshot's base, so the log is opened at that base; the state machine
        // is then restored from the snapshot and the tail re-applied.
        RaftLog replayed = new RaftLog(new WriteAheadLog(dir.resolve("raft-log.bin")),
                snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());
        ReplicatedKeyValueStore rebuilt = new ReplicatedKeyValueStore();
        RaftConsensus restarted = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                null, null, RaftMetadataStore.inMemory(), replayed, rebuilt, appliedStore, snapshotStore, 2);

        assertEquals(5, restarted.getLastApplied());
        for (int i = 1; i <= 5; i++) {
            assertArrayEquals(value(i), rebuilt.get("k" + i));
        }
        assertEquals(5, replayed.firstIndex(), "The log base must be restored from the snapshot");
        assertEquals(5, replayed.lastIndex());
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
     * A state machine that counts applied entries but cannot snapshot.
     */
    private static final class EchoStateMachine implements StateMachine {
        final AtomicInteger applied = new AtomicInteger();

        @Override
        public void apply(LogEntry entry) {
            applied.incrementAndGet();
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

        void register(RaftConsensus node) {
            nodes.put(node.getNodeId(), node);
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
                            request.lastIncludedIndex(), request.lastIncludedTerm(), request.data(), request.config());
                    term = target.getCurrentTerm();
                }
                return new InstallSnapshotResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(), term, success);
            }, executor);
        }
    }
}
