package com.minigoogle.cluster;

import com.minigoogle.cluster.state.KvCommand;
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
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Config-change entries replicated through the Raft log: add/remove commit like
 * any other entry, the committed configuration on every node converges to the
 * target set, the quorum during a pending change covers the old and new
 * configurations (max old/new majority), one change at a time is enforced, a
 * removed node stops counting toward the quorum, and a leader removed by its own
 * committed change steps down so the survivors re-elect.
 */
class RaftConsensusConfigChangeTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    private static final byte[] K1 = "k".getBytes(StandardCharsets.UTF_8);
    private static final byte[] V1 = "v1".getBytes(StandardCharsets.UTF_8);

    @Test
    void testAddNodeCommitsAndEveryNodeAdoptsNewConfig() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus a = newMember("a", transport);
        RaftConsensus b = newMember("b", transport);
        RaftConsensus c = newMember("c", transport);
        transport.register(a);
        transport.register(b);
        transport.register(c);

        try {
            makeLeader(a);
            int index = a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.ADD, "d"));
            assertEquals(1, index);

            assertTrue(waitUntil(() -> a.getCommitIndex() == 1, 3000),
                    "The add must commit on the old config's majority without the joining server's ack");
            assertEquals(Set.of("a", "b", "c", "d"), a.getCommittedConfig().members());

            assertTrue(pumpUntil(a, () -> b.getCommittedConfig().size() == 4, 3000),
                    "The follower must apply the committed change");
            assertTrue(pumpUntil(a, () -> c.getCommittedConfig().size() == 4, 3000));
            assertEquals(Set.of("a", "b", "c", "d"), b.getCommittedConfig().members());
            assertEquals(Set.of("a", "b", "c", "d"), c.getCommittedConfig().members());
        } finally {
            a.stop();
            b.stop();
            c.stop();
            transport.shutdown();
        }
    }

    @Test
    void testRemoveNodeAndRemovedNodeStopsCountingTowardQuorum() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus a = newMember("a", transport);
        RaftConsensus b = newMember("b", transport);
        RaftConsensus c = newMember("c", transport);
        transport.register(a);
        transport.register(b);
        transport.register(c);

        try {
            makeLeader(a);

            // A dead member of the established config must not block commit:
            // the quorum stays 2 of 3.
            transport.goSilent("c");
            a.appendEntry(K1);
            assertTrue(waitUntil(() -> a.getCommitIndex() == 1, 3000),
                    "Leader + one live member is a majority of the committed config");
            transport.unSilent("c");

            int index = a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.REMOVE, "c"));
            assertEquals(2, index);
            assertTrue(waitUntil(() -> a.getCommitIndex() == 2, 3000),
                    "The removal must commit on the old config's majority");
            assertEquals(Set.of("a", "b"), a.getCommittedConfig().members());

            // After the removal, c no longer counts: a alone is not a majority
            // of {a, b}, even though a + c would be two servers.
            transport.goSilent("b");
            a.appendEntry(K1);
            Thread.sleep(300);
            assertEquals(2, a.getCommitIndex(), "Removed c must not count toward the {a, b} quorum");
            transport.unSilent("b");
            a.sendHeartbeats();
            assertTrue(waitUntil(() -> a.getCommitIndex() == 3, 3000),
                    "The recovered member restores the quorum");
        } finally {
            a.stop();
            b.stop();
            c.stop();
            transport.shutdown();
        }
    }

    @Test
    void testLeaderRemovedByCommittedChangeStepsDownAndSurvivorsReelect() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus a = newMember("a", transport);
        RaftConsensus b = newMember("b", transport);
        RaftConsensus c = newMember("c", transport);
        transport.register(a);
        transport.register(b);
        transport.register(c);

        try {
            makeLeader(a);
            int index = a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.REMOVE, "a"));
            assertEquals(1, index);

            assertTrue(waitUntil(() -> a.getCommitIndex() == 1, 3000));
            assertEquals(Set.of("b", "c"), a.getCommittedConfig().members());
            assertTrue(waitUntil(() -> a.getState() == RaftConsensus.RaftState.FOLLOWER, 3000),
                    "A leader removed by its own committed change must step down");

            // The survivors re-elect over the real protocol; the removed leader
            // still participates until it becomes a non-member, so it votes for b.
            b.startElection();
            assertTrue(waitUntil(() -> b.getState() == RaftConsensus.RaftState.LEADER, 3000),
                    "The survivors must re-elect a leader");

            // The new leader commits a current-term entry, which indirectly
            // commits the removal entry it had replicated but not yet applied.
            b.appendEntry(K1);
            assertTrue(waitUntil(() -> b.getCommitIndex() == 2
                    && b.getCommittedConfig().members().equals(Set.of("b", "c")), 3000),
                    "The new leader must commit its term entry and adopt the {b, c} configuration");
        } finally {
            a.stop();
            b.stop();
            c.stop();
            transport.shutdown();
        }
    }

    @Test
    void testOnlyOneConfigChangeInFlight() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus a = newMember("a", transport);
        RaftConsensus b = newMember("b", transport);
        RaftConsensus c = newMember("c", transport);
        transport.register(a);
        transport.register(b);
        transport.register(c);

        try {
            makeLeader(a);

            // Silence the quorum so the first change can never commit: the
            // pending window must be stable while we probe the rejection.
            transport.goSilent("b");
            transport.goSilent("c");
            int index = a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.ADD, "d"));
            assertEquals(1, index);

            assertThrows(IllegalStateException.class,
                    () -> a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.REMOVE, "b")));

            transport.unSilent("b");
            transport.unSilent("c");
            a.sendHeartbeats();
            assertTrue(waitUntil(() -> a.getCommitIndex() == 1, 3000),
                    "The first change must commit once the quorum returns");
            int second = a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.REMOVE, "b"));
            assertEquals(2, second);
        } finally {
            a.stop();
            b.stop();
            c.stop();
            transport.shutdown();
        }
    }

    @Test
    void testConfigChangeRequiresLeaderAndEstablishedConfig() {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus a = newMember("a", transport);
        RaftConsensus b = newMember("b", transport);
        transport.register(a);
        transport.register(b);

        try {
            assertThrows(IllegalStateException.class,
                    () -> b.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.ADD, "x")));

            // A leader in bootstrap mode (no established config) must reject the change.
            RaftConsensus solo = new RaftConsensus("solo", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 1, null, null);
            solo.startElection();
            assertTrue(solo.receiveVote());
            solo.becomeLeader();
            assertThrows(IllegalStateException.class,
                    () -> solo.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.ADD, "y")));
            solo.stop();
        } finally {
            a.stop();
            b.stop();
            transport.shutdown();
        }
    }

    @Test
    void testConfigChangeIsConsumedByConsensusNotStateMachine() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RecordingStateMachine stateMachine = new RecordingStateMachine();
        RaftConsensus a = new RaftConsensus("a", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                stateMachine, null, null, 0, RaftConfigurationStore.inMemory());
        a.initializeConfig(List.of("a", "b", "c"));
        RaftConsensus b = newMember("b", transport);
        RaftConsensus c = newMember("c", transport);
        transport.register(a);
        transport.register(b);
        transport.register(c);

        try {
            makeLeader(a);
            a.appendEntry(KvCommand.encodePut("k", V1));
            a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.ADD, "d"));

            assertTrue(waitUntil(() -> a.getCommitIndex() == 2, 3000));
            assertTrue(waitUntil(() -> stateMachine.applied.equals(List.of(1)), 3000),
                    "Config-change entries must be consumed by the consensus layer, never the state machine");
            assertEquals(Set.of("a", "b", "c", "d"), a.getCommittedConfig().members());
        } finally {
            a.stop();
            b.stop();
            c.stop();
            transport.shutdown();
        }
    }

    @Test
    void testBootstrapNodeAdoptsConfigOnLogReplication() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus a = newMember("a", transport);
        RaftConsensus b = newMember("b", transport);
        RaftConsensus c = newMember("c", transport);
        // A fresh process in bootstrap mode: no initializeConfig, empty log.
        RaftConsensus d = new RaftConsensus("d", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c", "d"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                null, null, null, 0, RaftConfigurationStore.inMemory());
        transport.register(a);
        transport.register(b);
        transport.register(c);
        transport.register(d);

        try {
            makeLeader(a);
            int index = a.appendConfigChange(new ConfigChange(ConfigChange.ChangeType.ADD, "d"));
            assertEquals(1, index);

            assertTrue(waitUntil(() -> a.getCommitIndex() == 1, 3000));
            assertTrue(pumpUntil(a, () -> d.getCommittedConfig().members().equals(Set.of("a", "b", "c", "d")), 3000),
                    "A bootstrap node must adopt the committed config it joins from the leader's AppendEntries, "
                            + "not end up a one-member cluster: " + d.getCommittedConfig().members());
            assertTrue(d.getConfigEstablished(), "The adopted configuration must be established");
            assertEquals(3, d.getCommittedConfig().majority(), "The four-member config must demand a majority of 3");
        } finally {
            a.stop();
            b.stop();
            c.stop();
            d.stop();
            transport.shutdown();
        }
    }

    private RaftConsensus newMember(String id, FakeRaftTransport transport) {
        RaftConsensus node = new RaftConsensus(id, ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, () -> List.of("a", "b", "c"), RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                null, null, null, 0, RaftConfigurationStore.inMemory());
        node.initializeConfig(List.of("a", "b", "c"));
        return node;
    }

    private void makeLeader(RaftConsensus node) {
        node.startElection();
        assertTrue(node.receiveVote(), "Majority must win the election");
        node.becomeLeader();
        assertEquals(RaftConsensus.RaftState.LEADER, node.getState());
    }

    /**
     * Records every entry forwarded to it. If a config-change frame ever
     * leaked through the consensus layer this list would contain its index;
     * the interception is verified by asserting only KV entries appear.
     */
    private static final class RecordingStateMachine implements StateMachine {
        private final List<Integer> applied = new CopyOnWriteArrayList<>();

        @Override
        public void apply(LogEntry entry) {
            applied.add(entry.index());
        }

        @Override
        public boolean isSnapshotable() {
            return false;
        }

        @Override
        public byte[] snapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restore(byte[] data) {
            throw new UnsupportedOperationException();
        }
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
                            request.prevLogIndex(), request.prevLogTerm(), request.entries(),
                            request.leaderCommit(), request.config());
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
                            request.lastIncludedIndex(), request.lastIncludedTerm(), request.data(), request.config());
                    term = target.getCurrentTerm();
                }
                return new InstallSnapshotResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(), term, success);
            }, executor);
        }
        @Override
        public CompletableFuture<ReadIndexResponse> sendReadIndex(String targetNodeId, ReadIndexRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                RaftConsensus target = nodes.get(targetNodeId);
                if (target == null) {
                    return new ReadIndexResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                            request.correlationId(), targetNodeId, System.currentTimeMillis(), 0, 0, false);
                }
                RaftConsensus.ReadIndexResult result = target.readIndex();
                return new ReadIndexResponse(ClusterProtocol.PROTOCOL_VERSION, request.requestId(),
                        request.correlationId(), targetNodeId, System.currentTimeMillis(),
                        result.term(), result.commitIndex(), result.success());
            }, executor);
        }
    }
}