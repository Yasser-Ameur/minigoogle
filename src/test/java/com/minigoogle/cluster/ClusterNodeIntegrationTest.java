package com.minigoogle.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.routing.BroadcastQueryRouter;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.http.HttpSearchTransport;
import com.minigoogle.distributed.query.coordinator.DistributedSearchCoordinator;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterNodeIntegrationTest {

    private static final long CONVERGENCE_DEADLINE_MS = 8000;

    private ClusterNode node1;
    private ClusterNode node2;
    private ClusterNode node3;
    private NodeDirectory directory;

    @BeforeEach
    void setUp() throws IOException {
        // Fast gossip for tests
        long gossipInterval = 100;
        long timeout = 500;
        long raftElection = 400;
        long raftHeartbeat = 150;

        Map<String, Integer> portMap = new ConcurrentHashMap<>();
        portMap.put("node-1", 9091);
        portMap.put("node-2", 9092);
        portMap.put("node-3", 9093);

        directory = nodeId -> {
            Integer port = portMap.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        SearchExecutor node2Search = new LocalSearchExecutor(2, (query, topK) -> List.of(
                new SearchResult("http://node-2/result", "Node 2: " + query, "remote shard", 0.85, 0.7, 0.15)));

        ClusterSecurity security = new ClusterSecurity("integration-secret");

        node1 = new ClusterNode("node-1", 9091, directory, gossipInterval, timeout, raftElection, raftHeartbeat, null, security);
        node2 = new ClusterNode("node-2", 9092, directory, gossipInterval, timeout, raftElection, raftHeartbeat, node2Search, security);
        node3 = new ClusterNode("node-3", 9093, directory, gossipInterval, timeout, raftElection, raftHeartbeat, null, security);

        // Pre-populate some seed knowledge to bootstrap gossip without a proper discovery layer yet
        // In real life, seed nodes are injected at startup
        node1.getGossip().seedPeer("node-2");
        node2.getGossip().seedPeer("node-3");

        node1.start();
        node2.start();
        node3.start();
    }

    @AfterEach
    void tearDown() {
        if (node1 != null) node1.stop();
        if (node2 != null) node2.stop();
        if (node3 != null) node3.stop();
    }

    /**
     * Waits until the condition holds or the deadline passes.
     * Polling (rather than a fixed sleep) asserts the real property —
     * eventual convergence — without being brittle to gossip's randomized
     * single-peer selection and failure-detection timing.
     */
    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private boolean allLiveSetsConverged() {
        Set<String> expected = Set.of("node-1", "node-2", "node-3");
        return Set.copyOf(node1.getGossip().getLiveNodes()).equals(expected)
                && Set.copyOf(node2.getGossip().getLiveNodes()).equals(expected)
                && Set.copyOf(node3.getGossip().getLiveNodes()).equals(expected);
    }

    @Test
    void testGossipDiscovery() throws InterruptedException {
        // Wait for gossip to propagate and converge
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge. node-1=" + node1.getGossip().getLiveNodes()
                        + " node-2=" + node2.getGossip().getLiveNodes()
                        + " node-3=" + node3.getGossip().getLiveNodes());
    }

    @Test
    void testRingIntegrationWithGossip() throws InterruptedException {
        // Wait for gossip to propagate and converge
        assertTrue(waitUntil(() -> node1.getRing().nodeCount() == 3
                && node2.getRing().nodeCount() == 3
                && node3.getRing().nodeCount() == 3, CONVERGENCE_DEADLINE_MS),
                "Rings did not converge. node-1=" + node1.getRing().nodeCount()
                        + " node-2=" + node2.getRing().nodeCount()
                        + " node-3=" + node3.getRing().nodeCount());

        // Verify that key routing works — any key should resolve to a valid node
        String owner = node1.getRing().getNode("test-document-123");
        assertTrue(owner.equals("node-1") || owner.equals("node-2") || owner.equals("node-3"),
                "Key should route to a valid cluster node");
    }

    @Test
    void testRaftLeaderElectedOverTransport() throws InterruptedException {
        // Raft resolves its peers from gossip, so wait for the membership table
        // to converge before expecting any campaign to reach a majority.
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge: " + node1.getGossip().getLiveNodes());

        assertTrue(waitUntil(() -> exactlyOneLeader(), CONVERGENCE_DEADLINE_MS),
                "No single leader elected over the transport. node-1=" + node1.getRaft().getState()
                        + " node-2=" + node2.getRaft().getState()
                        + " node-3=" + node3.getRaft().getState());

        // The leader must be a member of the cluster and hold a real term.
        int leaders = (node1.getRaft().getState() == RaftConsensus.RaftState.LEADER ? 1 : 0)
                + (node2.getRaft().getState() == RaftConsensus.RaftState.LEADER ? 1 : 0)
                + (node3.getRaft().getState() == RaftConsensus.RaftState.LEADER ? 1 : 0);
        assertEquals(1, leaders);
        RaftConsensus leader = node1.getRaft().getState() == RaftConsensus.RaftState.LEADER ? node1.getRaft()
                : node2.getRaft().getState() == RaftConsensus.RaftState.LEADER ? node2.getRaft() : node3.getRaft();
        assertTrue(leader.getCurrentTerm() >= 1, "Elected leader should hold a real term");
    }

    @Test
    void testRaftEntryReplicatesAndCommitsOverHttp() throws InterruptedException {
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge: " + node1.getGossip().getLiveNodes());
        assertTrue(waitUntil(() -> exactlyOneLeader(), CONVERGENCE_DEADLINE_MS),
                "No single leader elected over the transport");

        // Leadership is not stable across the append-and-verify cycle. Two
        // distinct races make a single-shot attempt flaky:
        //   1. An election can fire between locating the leader and appending,
        //      so the node rejects the append ("Only the leader may append").
        //   2. A leader that accepts an entry and then loses leadership before
        //      replicating it never replicates that entry at all — Raft
        //      explicitly permits discarding uncommitted entries on a term
        //      change, so waiting on that index forever is simply wrong.
        // Retry the whole cycle against the current leader until one attempt
        // carries an entry all the way to replication and commit. This asserts
        // the real property (the cluster eventually replicates and commits over
        // real HTTP) without weakening either check.
        long overallDeadline = System.currentTimeMillis() + 30_000;
        boolean replicatedAndCommitted = false;
        int attempts = 0;

        while (System.currentTimeMillis() < overallDeadline && !replicatedAndCommitted) {
            final RaftConsensus leader;
            final int index;
            try {
                leader = currentLeader();
                index = leader.appendEntry(
                        "replicate-me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IllegalStateException | AssertionError leadershipChanged) {
                Thread.sleep(20);
                continue;
            }
            attempts++;

            // Stop waiting the moment leadership is lost: this attempt's entry
            // may never replicate, and the next iteration retries on the new
            // leader rather than burning the deadline on a doomed index.
            boolean stillLeader = waitUntil(
                    () -> followersReplicated(index)
                            || leader.getState() != RaftConsensus.RaftState.LEADER,
                    CONVERGENCE_DEADLINE_MS)
                    && leader.getState() == RaftConsensus.RaftState.LEADER;
            if (!stillLeader) {
                continue;
            }

            waitUntil(() -> leader.getCommitIndex() >= index
                            || leader.getState() != RaftConsensus.RaftState.LEADER,
                    CONVERGENCE_DEADLINE_MS);
            replicatedAndCommitted = followersReplicated(index)
                    && leader.getCommitIndex() >= index;
        }

        assertTrue(replicatedAndCommitted,
                "An entry must replicate to every follower over real HTTP and commit "
                        + "on the leader (attempts: " + attempts + ")");
    }

    private RaftConsensus currentLeader() {
        if (node1.getRaft().getState() == RaftConsensus.RaftState.LEADER) return node1.getRaft();
        if (node2.getRaft().getState() == RaftConsensus.RaftState.LEADER) return node2.getRaft();
        if (node3.getRaft().getState() == RaftConsensus.RaftState.LEADER) return node3.getRaft();
        throw new AssertionError("No leader found");
    }

    private boolean followersReplicated(int index) {
        for (ClusterNode node : List.of(node1, node2, node3)) {
            if (node.getRaft().getState() != RaftConsensus.RaftState.LEADER
                    && node.getRaft().getLastLogIndex() < index) {
                return false;
            }
        }
        return true;
    }

    private boolean exactlyOneLeader() {
        long leaders = 0;
        if (node1.getRaft().getState() == RaftConsensus.RaftState.LEADER) leaders++;
        if (node2.getRaft().getState() == RaftConsensus.RaftState.LEADER) leaders++;
        if (node3.getRaft().getState() == RaftConsensus.RaftState.LEADER) leaders++;
        return leaders == 1;
    }

    @Test
    void testDistributedSearchFansOutToRemoteNodeOverTransport() throws InterruptedException {
        // Wait for gossip to converge so the broadcast router sees all nodes
        assertTrue(waitUntil(this::allLiveSetsConverged, CONVERGENCE_DEADLINE_MS),
                "Gossip did not converge: " + node1.getGossip().getLiveNodes());

        ObjectMapper mapper = new ObjectMapper();
        ClusterSecurity security = new ClusterSecurity("integration-secret");
        HttpSearchTransport transport = new HttpSearchTransport(directory, mapper, "node-1", security.deriveToken("node-1"));

        List<LocalSearchExecutor> localExecutors = List.of(new LocalSearchExecutor(1, (query, topK) -> List.of(
                new SearchResult("http://node-1/result", "Node 1: " + query, "local shard", 0.9, 0.8, 0.1))));

        DistributedSearchCoordinator coordinator = new DistributedSearchCoordinator(
                new BroadcastQueryRouter(node1.getGossip()),
                transport,
                localExecutors,
                "node-1",
                4,
                Duration.ofSeconds(5),
                100);

        SearchResponse response = coordinator.search("distributed systems", 10);

        // node-1 resolves locally, node-2 answers over real HTTP, node-3 (no local
        // search) returns 503 and is skipped by the executor.
        assertEquals(2, response.results().size(), "Expected local + remote results: " + response.results());
        List<String> urls = response.results().stream().map(SearchResult::url).toList();
        assertTrue(urls.contains("http://node-1/result"));
        assertTrue(urls.contains("http://node-2/result"));
        coordinator.shutdown();
    }
}
