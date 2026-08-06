package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Raft leader election over a shared transport, with the RPCs
 * delivered through a fake transport instead of HTTP. Fast, deterministic,
 * and exercises the same code path ClusterNode uses: candidates send
 * RequestVote RPCs, votes are counted, a leader emerges and starts sending
 * AppendEntries heartbeats.
 */
class RaftConsensusClusterTest {

    private static final long ELECTION_TIMEOUT_MS = 300;
    private static final long HEARTBEAT_MS = 100;

    @Test
    void testLeaderElectedOverTransportAndHeartbeatsFlowing() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus n1 = new RaftConsensus("n1", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3, transport, () -> List.of("n1", "n2", "n3"));
        RaftConsensus n2 = new RaftConsensus("n2", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3, transport, () -> List.of("n1", "n2", "n3"));
        RaftConsensus n3 = new RaftConsensus("n3", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 3, transport, () -> List.of("n1", "n2", "n3"));
        transport.register(n1);
        transport.register(n2);
        transport.register(n3);

        n1.start();
        n2.start();
        n3.start();

        try {
            assertTrue(waitUntil(() -> exactlyOneLeader(n1, n2, n3), 5000),
                    "A leader should be elected. n1=" + n1.getState() + " n2=" + n2.getState()
                            + " n3=" + n3.getState() + " votes=" + transport.requestVotes);

            RaftConsensus leader = leaderOf(n1, n2, n3);
            assertTrue(leader.getCurrentTerm() >= 1);
            assertEquals(leader.getNodeId(), leader.getCurrentLeader());

            // The leader must have run at least one election (sent RequestVotes)
            // and must be actively sending AppendEntries heartbeats.
            assertTrue(!transport.requestVotes.isEmpty(), "A candidate should have sent RequestVotes");
            assertTrue(waitUntil(() -> transport.heartbeats.size() > 0, 2000),
                    "The leader should send AppendEntries heartbeats");
        } finally {
            n1.stop();
            n2.stop();
            n3.stop();
            transport.shutdown();
        }
    }

    @Test
    void testHigherTermResponseStepsDownTheLeader() throws InterruptedException {
        FakeRaftTransport transport = new FakeRaftTransport();
        RaftConsensus leader = new RaftConsensus("leader", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        RaftConsensus follower = new RaftConsensus("follower", ELECTION_TIMEOUT_MS, HEARTBEAT_MS, 2,
                transport, () -> List.of("leader", "follower"));
        transport.register(leader);
        transport.register(follower);

        try {
            // Drive the election manually: term 1, self vote + one granted vote
            // wins. The leader is promoted explicitly so the test does not
            // depend on the timing of the async vote response.
            leader.startElection();
            assertTrue(leader.receiveVote(), "Majority of 2 should win the election");
            leader.becomeLeader();
            assertEquals(RaftConsensus.RaftState.LEADER, leader.getState());

            // The follower now answers with a higher term — the leader must step down.
            transport.setFollowerTerm("follower", 9);
            leader.sendHeartbeats();

            assertTrue(waitUntil(() -> leader.getState() != RaftConsensus.RaftState.LEADER, 3000),
                    "A leader that observes a higher term must step down");
            assertEquals(9, leader.getCurrentTerm());
            assertEquals(RaftConsensus.RaftState.FOLLOWER, leader.getState());
        } finally {
            leader.stop();
            follower.stop();
            transport.shutdown();
        }
    }

    private boolean exactlyOneLeader(RaftConsensus... nodes) {
        long leaders = 0;
        for (RaftConsensus node : nodes) {
            if (node.getState() == RaftConsensus.RaftState.LEADER) {
                leaders++;
            }
        }
        return leaders == 1;
    }

    private RaftConsensus leaderOf(RaftConsensus... nodes) {
        for (RaftConsensus node : nodes) {
            if (node.getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        throw new AssertionError("No leader found");
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
     * An in-memory RaftTransport that routes RPCs into the target
     * RaftConsensus node, exactly as RaftHandler does over HTTP.
     *
     * <p>Delivery is asynchronous (virtual threads), mirroring how the real
     * HTTP client completes futures on its own threads. A synchronous
     * delivery would deadlock: two nodes campaigning simultaneously would
     * hold their own locks while calling into each other's synchronized
     * methods.</p>
     */
    private static class FakeRaftTransport implements RaftTransport {
        final List<String> requestVotes = new CopyOnWriteArrayList<>();
        final List<String> heartbeats = new CopyOnWriteArrayList<>();
        private final Map<String, RaftConsensus> nodes = new ConcurrentHashMap<>();
        private final Map<String, Integer> forcedTerms = new ConcurrentHashMap<>();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        void register(RaftConsensus node) {
            nodes.put(node.getNodeId(), node);
        }

        void setFollowerTerm(String nodeId, int term) {
            forcedTerms.put(nodeId, term);
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
            requestVotes.add(request.candidateId() + " -> " + targetNodeId);
            return CompletableFuture.supplyAsync(() -> {
                RaftConsensus target = nodes.get(targetNodeId);
                boolean granted = target.receiveVoteRequest(request.candidateId(), request.term());
                int term = forcedTerms.getOrDefault(targetNodeId, target.getCurrentTerm());
                return new RequestVoteResponse(
                        ClusterProtocol.PROTOCOL_VERSION,
                        request.requestId(),
                        request.correlationId(),
                        targetNodeId,
                        System.currentTimeMillis(),
                        term,
                        granted);
            }, executor);
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request) {
            heartbeats.add(request.leaderId() + " -> " + targetNodeId);
            return CompletableFuture.supplyAsync(() -> {
                RaftConsensus target = nodes.get(targetNodeId);
                target.receiveHeartbeat(request.leaderId(), request.term());
                int term = forcedTerms.getOrDefault(targetNodeId, target.getCurrentTerm());
                return new AppendEntriesResponse(
                        ClusterProtocol.PROTOCOL_VERSION,
                        request.requestId(),
                        request.correlationId(),
                        targetNodeId,
                        System.currentTimeMillis(),
                        term,
                        true);
            }, executor);
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeId, InstallSnapshotRequest request) {
            return CompletableFuture.supplyAsync(() -> {
                RaftConsensus target = nodes.get(targetNodeId);
                boolean success = false;
                int term = forcedTerms.getOrDefault(targetNodeId, request.term());
                if (target != null) {
                    success = target.receiveInstallSnapshot(request.leaderId(), request.term(),
                            request.lastIncludedIndex(), request.lastIncludedTerm(), request.data(), request.config());
                    term = forcedTerms.getOrDefault(targetNodeId, target.getCurrentTerm());
                }
                return new InstallSnapshotResponse(
                        ClusterProtocol.PROTOCOL_VERSION,
                        request.requestId(),
                        request.correlationId(),
                        targetNodeId,
                        System.currentTimeMillis(),
                        term,
                        success);
            }, executor);
        }
    }
}
