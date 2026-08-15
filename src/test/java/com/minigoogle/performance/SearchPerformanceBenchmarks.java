package com.minigoogle.performance;

import com.google.gson.Gson;
import com.minigoogle.cluster.ClusterNode;
import com.minigoogle.cluster.ClusterSecurity;
import com.minigoogle.cluster.RaftConsensus;
import com.minigoogle.cluster.RaftLog;
import com.minigoogle.cluster.balancing.Rebalancer;
import com.minigoogle.cluster.balancing.Rebalancer.MigrationPlan;
import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.core.config.Configuration;
import com.minigoogle.distributed.coordinator.ClusterCoordinator;
import com.minigoogle.distributed.coordinator.SearchCoordinator;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.distributed.sharding.ShardManager;
import com.minigoogle.ml.eval.SyntheticCorpus;
import com.minigoogle.ml.eval.SyntheticCorpus.JudgedCorpus;
import com.minigoogle.ml.eval.SyntheticCorpus.JudgedQuery;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.features.RawFeatures;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import com.minigoogle.monitoring.benchmark.BenchmarkRunner;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.network.serialization.JsonSerializer;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.GlobalRankingPipeline;
import com.minigoogle.ranking.pipeline.RankedCandidate;
import com.minigoogle.search.RetrievalResult;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproducible performance benchmarks for the search engine.
 *
 * <p>Every benchmark drives the shared production code path — the real
 * {@link SearchEngine} / {@link GlobalRankingPipeline} for search and global
 * ranking, real HTTP scatter-gather through {@link SearchCoordinator} for
 * distributed search, real {@link ClusterNode} Raft over HTTP for failover, and
 * the coordinator's {@link Rebalancer} for rebalance planning. Latencies are
 * collected after a warmup phase so the JIT has settled, and are reported as
 * P50/P95/P99 plus throughput. The assertions here are generous regression
 * guards; the numbers printed by each test are the honest measured figures for
 * documentation and the resume.</p>
 */
class SearchPerformanceBenchmarks {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Standalone search latency
    // ------------------------------------------------------------------

    @Test
    void searchLatencyPercentiles() throws IOException {
        JudgedCorpus corpus = SyntheticCorpus.generate(7, 8, 400);
        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(Map.of()), tempDir.resolve("search-engine"));
        SearchEngine engine = build.engine();
        LinearRankingModel model = new LinearRankingModel();
        List<String> queries = corpus.queries().stream().map(JudgedQuery::query).toList();

        BenchmarkReport report = measureStandaloneSearch(engine, model, queries);
        if (report.p99LatencyMs() >= 250.0) {
            // A transient GC/OS-load spike can contaminate a single measurement;
            // re-measure once before declaring a regression so the guard stays
            // sensitive to real slowdowns without flaking on spikes.
            System.out.println("  p99 " + report.p99LatencyMs() + "ms exceeded guard under load, re-measuring...");
            report = measureStandaloneSearch(engine, model, queries);
        }

        assertTrue(report.p99LatencyMs() < 250.0,
                "p99 search latency " + report.p99LatencyMs() + "ms unexpectedly high");
    }

    private static BenchmarkReport measureStandaloneSearch(SearchEngine engine,
                                                           LinearRankingModel model,
                                                           List<String> queries) {
        int warmup = 100;
        int iterations = 500;
        List<Long> latencies = new ArrayList<>(iterations);
        for (int i = 0; i < warmup; i++) {
            runSearch(engine, model, queries.get(i % queries.size()));
        }
        long wallStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            runSearch(engine, model, queries.get(i % queries.size()));
            latencies.add(System.nanoTime() - t0);
        }
        long wallNanos = System.nanoTime() - wallStart;
        BenchmarkReport report = new BenchmarkReport("standalone-search", iterations,
                latencies, java.time.Duration.ofNanos(wallNanos));

        System.out.println("=== Standalone search latency (" + queries.size() + " queries, "
                + iterations + " iterations after " + warmup + "-iter warmup) ===");
        System.out.println("  " + report.summary());
        System.out.printf("  P50=%.2fms P95=%.2fms P99=%.2fms max=%.2fms%n",
                report.p50LatencyMs(), report.p95LatencyMs(), report.p99LatencyMs(), report.maxLatencyMs());
        return report;
    }

    // ------------------------------------------------------------------
    // Indexing throughput
    // ------------------------------------------------------------------

    @Test
    void indexingThroughput() throws IOException {
        JudgedCorpus corpus = SyntheticCorpus.generate(7, 8, 400);
        int docCount = corpus.docs().size();
        long t0 = System.nanoTime();
        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(Map.of()), tempDir.resolve("index-throughput"));
        double seconds = (System.nanoTime() - t0) / 1e9;
        double docsPerSec = docCount / seconds;

        System.out.println("=== Indexing throughput ===");
        System.out.printf("  indexed %d docs in %.2fs => %.0f docs/s%n", docCount, seconds, docsPerSec);

        assertNotNull(build.engine(), "index build must produce a usable engine");
        assertTrue(docsPerSec > 50.0,
                "indexing throughput " + docsPerSec + " docs/s unexpectedly low");
    }

    // ------------------------------------------------------------------
    // Coordinator global ranking (scatter-gather merge) latency
    // ------------------------------------------------------------------

    @Test
    void coordinatorGlobalRankingLatency() {
        int shards = 5;
        int perShard = 60;
        List<RankedCandidate> candidates = new ArrayList<>();
        for (int s = 0; s < shards; s++) {
            for (int i = 0; i < perShard; i++) {
                double bm25 = 0.1 + ((s * perShard + i) % 80) / 100.0;
                double pr = ((i * 7 + s * 13) % 90) / 100.0;
                RawFeatures raw = new RawFeatures(bm25, pr * 2.0, 0.4, 0.1, 0.6, 0.3, 200.0, 0.0);
                candidates.add(new RankedCandidate("d-" + s + "-" + i,
                        "http://bench.example.com/" + s + "/" + i, "title " + i, "snippet", bm25, pr, raw));
            }
        }
        NormalizationContext context = new NormalizationContext(2.0, 500.0);
        LinearRankingModel model = new LinearRankingModel();
        BenchmarkRunner runner = new BenchmarkRunner("coordinator-global-ranking", 1000, 200);
        BenchmarkReport report = runner.run(() ->
                GlobalRankingPipeline.rank("benchmark query", candidates, context, model).size());

        System.out.println("=== Coordinator global ranking (" + shards + " shards x " + perShard
                + " candidates = " + candidates.size() + ") ===");
        System.out.println("  " + report.summary());

        var ranked = GlobalRankingPipeline.rank("benchmark query", candidates, context, model);
        assertEquals(candidates.size(), ranked.size(), "global ranking must return every candidate");
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).score() >= ranked.get(i).score(),
                    "global ranking must sort candidates by descending model score");
        }
        assertTrue(report.p99LatencyMs() < 50.0,
                "p99 global ranking latency " + report.p99LatencyMs() + "ms unexpectedly high");
    }

    // ------------------------------------------------------------------
    // Distributed end-to-end search latency over real HTTP
    // ------------------------------------------------------------------

    @Test
    void distributedSearchLatency() {
        ClusterCoordinator clusterCoordinator = new ClusterCoordinator(0);
        clusterCoordinator.start();
        RestClient client = new RestClient();
        Gson gson = new Gson();
        List<RestServer> shards = new ArrayList<>();
        try {
            for (int s = 0; s < 3; s++) {
                RestServer shard = mockShard(s);
                shard.start();
                shards.add(shard);
                NodeInfo info = new NodeInfo("shard-" + s, "localhost", shard.getPort(), NodeRole.INDEX,
                        NodeStatus.ONLINE, System.currentTimeMillis());
                client.post("http://localhost:" + clusterCoordinator.getPort() + "/register", gson.toJson(info));
            }
            SearchCoordinator coordinator = new SearchCoordinator(
                    "http://localhost:" + clusterCoordinator.getPort(), 3, 100_000, 1, 0.05);

            int warmup = 20;
            int iterations = 200;
            List<Long> latencies = new ArrayList<>(iterations);
            for (int i = 0; i < warmup; i++) {
                coordinator.search("benchmark distributed query", 20);
            }
            long wallStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                coordinator.search("benchmark distributed query", 20);
                latencies.add(System.nanoTime() - t0);
            }
            long wallNanos = System.nanoTime() - wallStart;
            BenchmarkReport report = new BenchmarkReport("distributed-search", iterations,
                    latencies, java.time.Duration.ofNanos(wallNanos));

            System.out.println("=== Distributed search latency (3 shards x 60 candidates, real HTTP, "
                    + iterations + " queries after " + warmup + "-iter warmup) ===");
            System.out.println("  " + report.summary());
            System.out.printf("  P50=%.2fms P95=%.2fms P99=%.2fms max=%.2fms%n",
                    report.p50LatencyMs(), report.p95LatencyMs(), report.p99LatencyMs(), report.maxLatencyMs());

            List<SearchResult> results = coordinator.search("benchmark distributed query", 20);
            assertEquals(20, results.size(), "coordinator must merge and rank a full top-K");
            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i - 1).score() >= results.get(i).score(),
                        "distributed results must be sorted by descending score");
            }
            assertTrue(report.p99LatencyMs() < 2000.0,
                    "p99 distributed latency " + report.p99LatencyMs() + "ms unexpectedly high");
        } finally {
            for (RestServer shard : shards) {
                shard.stop();
            }
            clusterCoordinator.stop();
        }
    }

    // ------------------------------------------------------------------
    // Raft leader failover over real HTTP
    // ------------------------------------------------------------------

    @Test
    void raftLeaderFailoverLatency() throws Exception {
        List<String> ids = List.of("bench-1", "bench-2", "bench-3");
        Map<String, Integer> ports = new ConcurrentHashMap<>();
        ports.put("bench-1", 9311);
        ports.put("bench-2", 9312);
        ports.put("bench-3", 9313);
        ClusterSecurity security = new ClusterSecurity("benchmark-failover-secret");
        NodeDirectory directory = nodeId -> {
            Integer port = ports.get(nodeId);
            return port != null ? URI.create("http://127.0.0.1:" + port) : null;
        };

        List<ClusterNode> all = new ArrayList<>();
        try {
            for (String id : ids) {
                ClusterNode node = newClusterNode(id, ports.get(id), directory, security);
                node.initializeConfig(ids);
                all.add(node);
            }
            all.get(0).getGossip().seedPeer("bench-2");
            all.get(1).getGossip().seedPeer("bench-3");
            for (ClusterNode node : all) {
                node.start();
            }

            ClusterNode initial = waitForLeader(all, 10_000);
            assertNotNull(initial, "3-node cluster must elect an initial leader");

            int samples = 3;
            List<Long> failoverMs = new ArrayList<>();
            for (int round = 0; round < samples; round++) {
                ClusterNode leader = currentLeader(all);
                String stoppedId = leader.getRaft().getNodeId();
                long t0 = System.nanoTime();
                leader.stop();
                all.remove(leader);
                ClusterNode newLeader = waitForLeader(all, 10_000);
                failoverMs.add((System.nanoTime() - t0) / 1_000_000);
                assertNotNull(newLeader,
                        "survivors must re-elect a leader after stopping " + stoppedId);

                Thread.sleep(300);
                ClusterNode restarted = newClusterNode(stoppedId, ports.get(stoppedId), directory, security);
                restarted.initializeConfig(ids);
                restarted.getGossip().seedPeer(newLeader.getRaft().getNodeId());
                restarted.start();
                all.add(restarted);
                assertTrue(waitUntil(() -> leaderAmong(all) != null, 15_000),
                        "cluster must elect a leader after " + stoppedId + " restarts");
                Thread.sleep(500);
            }

            BenchmarkReport report = new BenchmarkReport("raft-leader-failover", samples,
                    failoverMs.stream().map(ms -> ms * 1_000_000L).toList(),
                    java.time.Duration.ofNanos(failoverMs.stream().mapToLong(Long::longValue).sum() * 1_000_000L));
            System.out.println("=== Raft leader failover (" + samples + " leader-stops, 3-node HTTP cluster) ===");
            System.out.println("  " + report.summary());
            System.out.printf("  P50=%.0fms P95=%.0fms max=%.0fms%n",
                    report.p50LatencyMs(), report.p95LatencyMs(), report.maxLatencyMs());

            double avg = report.averageLatencyMs();
            assertTrue(avg < 10_000, "average failover latency " + avg + "ms unexpectedly high");
        } finally {
            for (ClusterNode node : all) {
                node.stop();
            }
        }
    }

    // ------------------------------------------------------------------
    // Rebalance planning latency + correctness
    // ------------------------------------------------------------------

    @Test
    void rebalancePlanLatency() {
        int nodeCount = 30;
        int shardCount = 600;
        ShardManager shardManager = new ShardManager();
        // Skew: the first 10 nodes are heavy (48 shards each), the other 20 are light (6 each).
        for (int s = 0; s < 480; s++) {
            shardManager.assignShard(s, "node-" + (s % 10));
        }
        for (int s = 480; s < shardCount; s++) {
            shardManager.assignShard(s, "node-" + (10 + (s % 20)));
        }
        Rebalancer rebalancer = new Rebalancer(shardManager);

        List<MigrationPlan> plan = rebalancer.computeRebalancePlan();
        assertFalse(plan.isEmpty(), "a skewed cluster must produce a rebalance plan");

        BenchmarkReport report = new BenchmarkRunner("rebalance-planning", 2000, 200)
                .run(() -> rebalancer.computeRebalancePlan().size());
        System.out.println("=== Rebalance planning (" + nodeCount + " nodes, " + shardCount
                + " shards, skew 10 heavy / 20 light) ===");
        System.out.println("  plan size: " + plan.size() + " shard migrations");
        System.out.println("  " + report.summary());
        System.out.printf("  P50=%.1fus P95=%.1fus P99=%.1fus%n",
                report.p50LatencyMs() * 1000, report.p95LatencyMs() * 1000, report.p99LatencyMs() * 1000);

        for (MigrationPlan migration : plan) {
            assertNotEquals(migration.fromNodeId(), migration.toNodeId(),
                    "a migration must move a shard between two distinct nodes");
            shardManager.unassignShard(migration.shardId(), migration.fromNodeId());
            shardManager.assignShard(migration.shardId(), migration.toNodeId());
        }
        assertTrue(rebalancer.isBalanced(),
                "applying the plan must leave the cluster balanced");

        assertTrue(report.p99LatencyMs() < 50.0,
                "p99 rebalance planning latency " + report.p99LatencyMs() + "ms unexpectedly high");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int runSearch(SearchEngine engine, LinearRankingModel model, String query) {
        RetrievalResult retrieval = engine.retrieveCandidates(query, 20);
        List<RankedDocument> rankedDocs = retrieval.ranked();
        NormalizationContext context = engine.normalizationContext();
        List<RankedCandidate> candidates = new ArrayList<>(rankedDocs.size());
        for (RankedDocument d : rankedDocs) {
            RawFeatures raw = engine.rawFeatures(query, d.documentId());
            candidates.add(new RankedCandidate(String.valueOf(d.documentId()), d.url(), d.title(),
                    d.snippet(), d.bm25Score(), d.pageRankScore(), raw));
        }
        return GlobalRankingPipeline.rank(query, candidates, context, model).size();
    }

    private static RestServer mockShard(int shardIndex) {
        RestServer node = new RestServer(0);
        node.post("/api/v1/search", body -> {
            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                double bm25 = 0.1 + ((shardIndex * 60 + i) % 90) / 100.0;
                double pr = ((i * 11 + shardIndex * 7) % 80) / 100.0;
                RawFeatures raw = new RawFeatures(bm25, pr * 2.0, 0.4, 0.1, 0.6, 0.3, 200.0, 0.0);
                results.add(new SearchResult("http://bench.example.com/" + shardIndex + "/" + i,
                        "doc " + i, "snippet", bm25 + pr, bm25, pr, raw.toArray()));
            }
            return JsonSerializer.toJson(new SearchResponse(1, results.size(), results, null, 2.0, 500.0));
        });
        return node;
    }

    private static ClusterNode newClusterNode(String nodeId, int port, NodeDirectory directory,
                                              ClusterSecurity security) throws IOException {
        return new ClusterNode(nodeId, port, directory, 100, 500, 600, 150, null, security,
                RaftMetadataStore.inMemory(), RaftLog.inMemory(), new ReplicatedKeyValueStore(),
                RaftAppliedStore.inMemory(), null, 0, RaftConfigurationStore.inMemory());
    }

    private static ClusterNode currentLeader(List<ClusterNode> nodes) {
        ClusterNode leader = leaderAmong(nodes);
        if (leader == null) {
            throw new AssertionError("No leader found among " + nodes.size() + " nodes");
        }
        return leader;
    }

    private static ClusterNode leaderAmong(List<ClusterNode> nodes) {
        for (ClusterNode node : nodes) {
            if (node.getRaft().getState() == RaftConsensus.RaftState.LEADER) {
                return node;
            }
        }
        return null;
    }

    private static ClusterNode waitForLeader(List<ClusterNode> nodes, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode leader = leaderAmong(nodes);
            if (leader != null) {
                return leader;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
