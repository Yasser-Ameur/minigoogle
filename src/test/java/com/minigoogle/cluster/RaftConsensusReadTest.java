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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linearizable read barrier over the fake transport: a leader with a live
 * quorum passes, a partitioned leader whose peers cannot ack its term fails,
 * a follower fails, and a single node trivially passes.
 */
class RaftConsensusReadTest {

    private static final long ELECTION_TIMEOUT_MS = 60_000; // keep nodes out of auto-election
    private static final long HEARTBEAT_MS = 50;

    @Test
    void testLeaderWithQuorumPassesBarrier() {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            assertTrue(leader.prepareReadBarrier(), "A leader with a live quorum must pass the barrier");
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testLeaderPassesBarrierWithMajorityOfThree() {
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

        try {
            makeLeader(leader);
            // One silent follower: self + one live follower is a majority of 3.
            transport.goSilent("f2");
            assertTrue(leader.prepareReadBarrier());
        } finally {
            leader.stop();
            f1.stop();
            f2.stop();
            transport.shutdown();
        }
    }

    @Test
    void testPartitionedLeaderFailsBarrier() {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            transport.goSilent("follower");
            assertFalse(leader.prepareReadBarrier(),
                    "A leader without a quorum of acks must not serve reads");
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testFollowerFailsBarrier() {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            assertFalse(follower.prepareReadBarrier(), "A follower must not serve linearizable reads");
            assertTrue(leader.prepareReadBarrier());
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    @Test
    void testSingleNodeTriviallyPassesBarrier() {
        RaftConsensus single = new RaftConsensus("solo", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 1);
        try {
            makeLeader(single);
            assertTrue(single.prepareReadBarrier(), "A single node is its own quorum");
        } finally {
            single.stop();
        }
    }

    @Test
    void testStepDownCompletesPendingBarrierFalse() throws Exception {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);

        try {
            makeLeader(leader);
            transport.goSilent("follower");

            // Step down concurrently with the barrier wait: the pending round
            // must fail fast rather than waiting out the timeout.
            CompletableFuture<Boolean> barrier = CompletableFuture.supplyAsync(leader::prepareReadBarrier);
            Thread.sleep(50);
            leader.receiveAppendEntries("new-leader", leader.getCurrentTerm() + 1, 0, 0, List.of(), 0);

            assertFalse(barrier.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "Stepping down must abort the in-flight read barrier");
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

    /**
     * An in-memory RaftTransport mirroring the HTTP handler dispatch. A node
     * put into silence answers success=false so the leader can never count it
     * toward a quorum.
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
