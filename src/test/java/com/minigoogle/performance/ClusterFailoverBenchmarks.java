package com.minigoogle.performance;

import com.minigoogle.cluster.ClusterNode;
import com.minigoogle.cluster.ClusterSecurity;
import com.minigoogle.cluster.NotLeaderException;
import com.minigoogle.cluster.RaftConsensus;
import com.minigoogle.cluster.transport.StaticNodeDirectory;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAILURE / END_TO_END — measures the cluster along the path the production
 * application wires, not an isolated component.
 *
 * <p>Every node here is built through {@link StaticNodeDirectory} parsed from a
 * {@code cluster.peers} string, a shared {@link ClusterSecurity} secret and a
 * durable per-node Raft directory — the same collaborators
 * {@code MiniGoogleApp.startClusterRuntime} uses — communicating over real HTTP.
 * What is measured is therefore what an operator actually runs.</p>
 *
 * <p>Reported: steady-state replicated-write latency and throughput, leader
 * election latency after a leader is killed, and the client-visible write
 * interruption across the failover.</p>
 */
class ClusterFailoverBenchmarks {

    private static final long DEADLINE_MS = 20_000;

    @TempDir
    Path tempDir;

    private final Map<String, ClusterNode> nodes = new LinkedHashMap<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private StaticNodeDirectory directory;
    private ClusterSecurity security;

    @AfterEach
    void tearDown() {
        for (ClusterNode node : nodes.values()) {
            try {
                node.stop();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        nodes.clear();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void formCluster(String... ids) throws IOException {
        security = new ClusterSecurity("failover-benchmark-secret");
        List<String> entries = new ArrayList<>();
        for (String id : ids) {
            int port = freePort();
            ports.put(id, port);
            entries.add(id + "=http://127.0.0.1:" + port);
        }
        directory = StaticNodeDirectory.parse(String.join(",", entries));
        for (String id : ids) {
            nodes.put(id, build(id));
        }
        for (ClusterNode n : nodes.values()) {
            n.start();
        }
        for (Map.Entry<String, ClusterNode> e : nodes.entrySet()) {
            for (String peer : directory.nodeIds()) {
                if (!peer.equals(e.getKey())) {
                    e.getValue().getGossip().seedPeer(peer);
                }
            }
        }
        for (ClusterNode n : nodes.values()) {
            n.initializeConfig(List.copyOf(directory.nodeIds()));
        }
    }

    private ClusterNode build(String nodeId) throws IOException {
        return new ClusterNode(nodeId, ports.get(nodeId), directory,
                200, 4_000, 1_200, 250, null, security, tempDir.resolve(nodeId));
    }

    private ClusterNode leader() {
        for (ClusterNode n : nodes.values()) {
            if (n.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return n;
            }
        }
        return null;
    }

    private boolean awaitLeader(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (leader() != null) {
                return true;
            }
            Thread.sleep(5);
        }
        return leader() != null;
    }

    /** Writes through the current leader, retrying across leadership changes. */
    private boolean write(String key, String value, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode candidate = leader();
            if (candidate == null) {
                Thread.sleep(5);
                continue;
            }
            try {
                candidate.put(key, value.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (NotLeaderException | IllegalStateException moved) {
                Thread.sleep(5);
            }
        }
        return false;
    }

    @Test
    void steadyStateReplicatedWriteLatency() throws Exception {
        formCluster("bench-a", "bench-b", "bench-c");
        assertTrue(awaitLeader(DEADLINE_MS), "cluster must elect a leader");

        int warmup = 20;
        int iterations = 100;
        for (int i = 0; i < warmup; i++) {
            write("warm:" + i, "v", DEADLINE_MS);
        }

        List<Long> latencies = new ArrayList<>(iterations);
        long wall = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            boolean ok = write("key:" + i, "value-" + i, DEADLINE_MS);
            latencies.add(System.nanoTime() - t0);
            assertTrue(ok, "steady-state write " + i + " must commit");
        }
        BenchmarkReport report = new BenchmarkReport("raft-replicated-write", iterations,
                latencies, Duration.ofNanos(System.nanoTime() - wall));

        System.out.println("=== Raft replicated write, 3-node cluster over real HTTP "
                + "(" + iterations + " committed writes after " + warmup + " warmup) ===");
        System.out.printf("  p50=%.2fms p95=%.2fms p99=%.2fms throughput=%.0f writes/s%n",
                report.p50LatencyMs(), report.p95LatencyMs(), report.p99LatencyMs(),
                iterations / (report.wallTime().toNanos() / 1e9));

        assertTrue(report.p99LatencyMs() < 2_000,
                "replicated write p99 " + report.p99LatencyMs() + "ms unexpectedly high");
    }

    @Test
    void leaderFailoverElectionAndWriteInterruption() throws Exception {
        formCluster("fail-a", "fail-b", "fail-c");
        assertTrue(awaitLeader(DEADLINE_MS), "cluster must elect an initial leader");

        int rounds = 3;
        List<Long> electionLatencies = new ArrayList<>(rounds);
        List<Long> interruptionLatencies = new ArrayList<>(rounds);

        for (int round = 0; round < rounds; round++) {
            // A committed write proves the cluster is healthy before the kill.
            assertTrue(write("pre:" + round, "v", DEADLINE_MS),
                    "cluster must accept a write before the leader is killed");

            ClusterNode victim = leader();
            assertNotNull(victim, "a leader must exist before failover");
            String victimId = nodes.entrySet().stream()
                    .filter(e -> e.getValue() == victim)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();

            long killedAt = System.nanoTime();
            victim.stop();
            nodes.remove(victimId);

            // Election latency: until a survivor declares itself leader.
            assertTrue(awaitLeader(DEADLINE_MS), "survivors must elect a new leader");
            electionLatencies.add(System.nanoTime() - killedAt);

            // Client-visible interruption: until a write commits again. This is
            // strictly longer than election, because the new leader must also
            // commit an entry of its own term.
            assertTrue(write("post:" + round, "v", DEADLINE_MS),
                    "the cluster must accept writes again after failover");
            interruptionLatencies.add(System.nanoTime() - killedAt);

            // Restore the node so the next round starts from a full cluster.
            ClusterNode restarted = build(victimId);
            nodes.put(victimId, restarted);
            restarted.start();
            for (String peer : directory.nodeIds()) {
                if (!peer.equals(victimId)) {
                    restarted.getGossip().seedPeer(peer);
                }
            }
            assertTrue(awaitLeader(DEADLINE_MS), "cluster must be healthy before the next round");
        }

        BenchmarkReport election = new BenchmarkReport("raft-election", rounds,
                electionLatencies, Duration.ofNanos(electionLatencies.stream().mapToLong(Long::longValue).sum()));
        BenchmarkReport interruption = new BenchmarkReport("raft-write-interruption", rounds,
                interruptionLatencies, Duration.ofNanos(interruptionLatencies.stream().mapToLong(Long::longValue).sum()));

        System.out.println("=== Raft leader failover, 3-node cluster over real HTTP ("
                + rounds + " leader kills, election timeout 1200ms) ===");
        System.out.printf("  election latency      p50=%.0fms max=%.0fms%n",
                election.p50LatencyMs(), election.maxLatencyMs());
        System.out.printf("  write interruption    p50=%.0fms max=%.0fms%n",
                interruption.p50LatencyMs(), interruption.maxLatencyMs());

        assertTrue(interruption.maxLatencyMs() < DEADLINE_MS,
                "write service must resume well within the deadline");
    }
}
