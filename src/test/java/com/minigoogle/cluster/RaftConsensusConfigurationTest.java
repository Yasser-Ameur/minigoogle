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
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The committed configuration as the source of quorum truth: it is restored
 * from {@link RaftConfigurationStore} before gossip converges, it overrides the
 * gossip/peer-supplier majority (a dead or absent member never shrinks the
 * quorum), and non-members are denied votes and elections.
 */
class RaftConsensusConfigurationTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    private static final byte[] K1 = "k".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Test
    void testInitializeConfigEstablishesAndPersists() throws IOException {
        RaftConfigurationStore store = new RaftConfigurationStore(dir.resolve("raft-config.bin"));
        RaftConsensus node = node("a", store, null, null);

        assertFalse(node.getConfigEstablished());
        assertEquals(ClusterConfiguration.EMPTY, node.getCommittedConfig());

        node.initializeConfig(List.of("a", "b", "c"));

        assertTrue(node.getConfigEstablished());
        assertEquals(Set.of("a", "b", "c"), node.getCommittedConfig().members());
        assertEquals(2, node.getCommittedConfig().majority());
        assertEquals(Set.of("a", "b", "c"), store.load().members());
    }

    @Test
    void testInitializeConfigRejectsSecondCall() {
        RaftConsensus node = node("a", RaftConfigurationStore.inMemory(), null, null);
        node.initializeConfig(List.of("a", "b", "c"));
        assertThrows(IllegalStateException.class, () -> node.initializeConfig(List.of("a", "b")));
    }

    @Test
    void testInitializeConfigRejectsEmptyMembers() {
        RaftConsensus node = node("a", RaftConfigurationStore.inMemory(), null, null);
        assertThrows(IllegalArgumentException.class, () -> node.initializeConfig(List.of()));
    }

    @Test
    void testRestartRestoresCommittedConfigBeforeGossipConverges() throws IOException {
        RaftConfigurationStore store = new RaftConfigurationStore(dir.resolve("raft-config.bin"));
        RaftConsensus first = node("a", store, null, null);
        first.initializeConfig(List.of("a", "b", "c"));
        first.stop();

        // Restarted node: same durable store, no transport, no peer supplier
        // (gossip has not converged yet) -- the config must still be known.
        RaftConsensus restarted = node("b", store, null, null);
        assertTrue(restarted.getConfigEstablished());
        assertEquals(Set.of("a", "b", "c"), restarted.getCommittedConfig().members());
        restarted.stop();
    }

    @Test
    void testCorruptConfigStoreFailsStartupFast() throws IOException {
        Path file = dir.resolve("raft-config.bin");
        Files.write(file, "GARBAGE".getBytes(StandardCharsets.UTF_8));
        RaftConfigurationStore store = new RaftConfigurationStore(file);
        assertThrows(UncheckedIOException.class, () -> node("a", store, null, null));
    }

    @Test
    void testSingleMemberConfigSelfCommits() {
        RaftConsensus node = node("a", RaftConfigurationStore.inMemory(), null, null);
        node.initializeConfig(List.of("a"));
        makeLeader(node);
        int index = node.appendEntry(K1);
        assertEquals(1, index);
        assertEquals(1, node.getCommitIndex(), "A single-member config commits on its own ack");
        assertEquals(1, node.getLastApplied());
    }

    @Test
    void testMajorityFollowsCommittedConfigNotGossipShrink() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        // Gossip has collapsed to the leader alone, but the committed config
        // is three servers: the quorum must stay 2, not shrink to 1.
        RaftConsensus a = node("a", RaftConfigurationStore.inMemory(), transport, () -> List.of("a"));
        RaftConsensus b = node("b", RaftConfigurationStore.inMemory(), transport, () -> List.of("a", "b", "c"));
        a.initializeConfig(List.of("a", "b", "c"));
        b.initializeConfig(List.of("a", "b", "c"));
        transport.register(a);
        transport.register(b);

        try {
            transport.goSilent("b");
            makeLeader(a);
            a.appendEntry(K1);
            Thread.sleep(300);
            assertEquals(0, a.getCommitIndex(),
                    "The committed-config majority is 2 of 3, so the leader alone must not commit");
            transport.unSilent("b");
            a.sendHeartbeats();
            assertTrue(waitUntil(() -> a.getCommitIndex() == 1, 3000),
                    "Leader + the live member is a majority of the committed config; the dead third member must not block it");
            assertEquals(1, a.getLastApplied());
        } finally {
            a.stop();
            b.stop();
            transport.shutdown();
        }
    }

    @Test
    void testBootstrapModeGossipShrinkDoesSelfCommit() {
        // Control for the test above: without an established config the quorum
        // still follows the peer supplier, so a leader alone (cluster of 1)
        // self-commits exactly as in bootstrap mode.
        RaftConsensus a = node("a", RaftConfigurationStore.inMemory(), null, () -> List.of("a"));
        makeLeader(a);
        a.appendEntry(K1);
        assertEquals(1, a.getCommitIndex());
    }

    @Test
    void testNonMemberVoteDeniedEvenWhenTermAdvances() {
        RaftConsensus b = node("b", RaftConfigurationStore.inMemory(), null, null);
        b.initializeConfig(List.of("a", "b", "c"));

        // A higher term from a non-member advances the term but never grants the vote.
        assertFalse(b.receiveVoteRequest("x", 5, 0, 0));
        assertEquals(5, b.getCurrentTerm());
        assertEquals(RaftConsensus.RaftState.FOLLOWER, b.getState());

        // Same term, still denied.
        assertFalse(b.receiveVoteRequest("x", 5, 0, 0));

        // A member candidate is granted.
        assertTrue(b.receiveVoteRequest("a", 6, 0, 0));
        assertEquals(6, b.getCurrentTerm());
        assertEquals("a", b.getVotedFor());
    }

    @Test
    void testNonMemberCannotCampaign() {
        RaftConsensus x = node("x", RaftConfigurationStore.inMemory(), null, null);
        x.initializeConfig(List.of("a", "b", "c"));

        x.startElection();

        assertEquals(RaftConsensus.RaftState.FOLLOWER, x.getState(), "A non-member must never campaign");
        assertEquals(0, x.getCurrentTerm());
    }

    private RaftConsensus node(String id, RaftConfigurationStore configStore, RaftTransport transport,
                               Supplier<List<String>> peerSupplier) {
        return new RaftConsensus(id, ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3,
                transport, peerSupplier, RaftMetadataStore.inMemory(), RaftLog.inMemory(),
                null, null, null, 0, configStore);
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