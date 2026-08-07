package com.minigoogle.distributed.coordinator;

import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.distributed.model.ShardInfo;
import com.minigoogle.distributed.registry.ClusterState;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.ml.ltr.RankingModel;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.serialization.JsonSerializer;
import com.minigoogle.ranking.pipeline.GlobalRankingPipeline;
import com.minigoogle.ranking.pipeline.RankedCandidate;
import com.minigoogle.ranking.pipeline.RankedResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates distributed search requests using the Scatter-Gather pattern.
 *
 * <p>Fetches the current cluster state from the ClusterCoordinator, fans the
 * query out to every online index node over the standard {@code POST
 * /api/v1/search} protocol, then produces the global top-K ranking.</p>
 *
 * <p>Shard-mode index nodes reply with the candidate set carrying raw feature
 * vectors and their corpus statistics (see {@link SearchResponse#maxPageRank()}
 * and {@link SearchResponse#maxDocLength()}). The coordinator merges the
 * candidates and ranks them with the shared {@link GlobalRankingPipeline}
 * against a global normalization context derived as the maximum over the
 * shard statistics — the exact same pipeline a standalone node uses, so the
 * served ordering is produced by identical code in both modes.</p>
 *
 * <p>When any shard replies without raw features (older or feature-less
 * nodes), the coordinator transparently falls back to a score-based top-K
 * merge.</p>
 */
public class SearchCoordinator {

    private final RestClient client;
    private final String clusterCoordinatorUrl;
    private final RankingModel rankingModel;
    private final int shardOversample;

    /**
     * @param clusterCoordinatorUrl The URL of the cluster coordinator's state
     *                              registry (e.g. {@code http://localhost:8081}).
     */
    public SearchCoordinator(String clusterCoordinatorUrl) {
        this(clusterCoordinatorUrl, 3);
    }

    /**
     * @param clusterCoordinatorUrl The URL of the cluster coordinator's state
     *                              registry.
     * @param shardOversample       How many candidates to request per shard for
     *                              every final result slot, so the coordinator
     *                              ranks from a superset (RFC 0001 §6).
     */
    public SearchCoordinator(String clusterCoordinatorUrl, int shardOversample) {
        this.client = new RestClient();
        this.clusterCoordinatorUrl = clusterCoordinatorUrl;
        this.rankingModel = new LinearRankingModel();
        this.shardOversample = Math.max(1, shardOversample);
    }

    /**
     * Executes a distributed search query.
     *
     * @param query The user's query string.
     * @param topK  The number of results to return.
     * @return List of globally ranked top K results.
     */
    public List<SearchResult> search(String query, int topK) {
        // 1. Fetch current cluster state
        ClusterState state = getClusterState();
        if (state == null) {
            return List.of();
        }

        // 2. Determine target nodes (one replica per shard, else every online index node)
        List<NodeInfo> targets = selectTargets(state);
        if (targets.isEmpty()) {
            return List.of();
        }

        // 3. Scatter: Send query to all target nodes asynchronously. Request an
        // oversampled candidate set per shard so global ranking has a superset
        // of the final page to choose from (RFC 0001 §6.2).
        int shardFetch = topK * shardOversample;
        String requestJson = JsonSerializer.toJson(new SearchRequest(query, 1, shardFetch));
        List<CompletableFuture<SearchResponse>> futures = new ArrayList<>();

        for (NodeInfo node : targets) {
            String url = "http://" + node.host() + ":" + node.port() + "/api/v1/search";
            CompletableFuture<SearchResponse> future = client.postAsync(url, requestJson)
                    .thenApply(body -> JsonSerializer.fromJson(body, SearchResponse.class))
                    .exceptionally(ex -> {
                        System.err.println("Shard query failed: " + ex.getMessage());
                        return null;
                    });
            futures.add(future);
        }

        // 4. Gather: Wait for all responses
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 5. Merge and rank
        List<SearchResponse> responses = new ArrayList<>(futures.size());
        for (CompletableFuture<SearchResponse> f : futures) {
            try {
                SearchResponse response = f.get();
                if (response != null && response.results() != null) {
                    responses.add(response);
                }
            } catch (Exception ignored) {
                // Failures are already handled in the exceptionally block
            }
        }

        return rankResponses(query, responses, topK);
    }

    /**
     * Merges the shard responses into a single global top-K list.
     *
     * <p>When every candidate carries raw features, candidates are deduplicated
     * by URL and ranked with {@link GlobalRankingPipeline} against a global
     * normalization context built from the maximum of the shard corpus stats.
     * Otherwise the responses are merged by score, preserving compatibility
     * with feature-less shards.</p>
     */
    private List<SearchResult> rankResponses(String query, List<SearchResponse> responses, int topK) {
        if (responses.isEmpty()) {
            return List.of();
        }

        double globalMaxPageRank = 0.0;
        double globalMaxDocLength = 0.0;
        boolean allFeatureful = true;
        Map<String, SearchResult> deduplicated = new LinkedHashMap<>();
        for (SearchResponse response : responses) {
            globalMaxPageRank = Math.max(globalMaxPageRank, response.maxPageRank());
            globalMaxDocLength = Math.max(globalMaxDocLength, response.maxDocLength());
            for (SearchResult result : response.results()) {
                if (result.features() == null) {
                    allFeatureful = false;
                }
                // First-seen order is the candidate order used for the POSITION feature.
                deduplicated.putIfAbsent(result.url(), result);
            }
        }
        List<SearchResult> candidates = new ArrayList<>(deduplicated.values());

        if (allFeatureful && !candidates.isEmpty()) {
            List<RankedCandidate> rankedCandidates = new ArrayList<>(candidates.size());
            for (SearchResult candidate : candidates) {
                rankedCandidates.add(new RankedCandidate(
                        candidate.url(), candidate.url(), candidate.title(), candidate.snippet(),
                        candidate.bm25Score(), candidate.pageRankScore(), candidate.rawFeatures()));
            }
            List<RankedResult> ranked = GlobalRankingPipeline.rank(
                    query, rankedCandidates,
                    new NormalizationContext(globalMaxPageRank, globalMaxDocLength),
                    rankingModel);

            List<SearchResult> finalResults = new ArrayList<>(Math.min(topK, ranked.size()));
            for (RankedResult result : ranked) {
                RankedCandidate candidate = result.candidate();
                finalResults.add(new SearchResult(
                        candidate.url(), candidate.title(), candidate.snippet(),
                        result.score(), candidate.bm25Score(), candidate.pageRankScore()));
            }
            return finalResults.size() > topK
                    ? finalResults.subList(0, topK)
                    : finalResults;
        }

        return mergeByScore(candidates, topK);
    }

    /**
     * Falls back to a score-based global top-K merge (min-heap) for shards
     * that reply without raw features.
     */
    private List<SearchResult> mergeByScore(List<SearchResult> candidates, int topK) {
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(SearchResult::score));
        for (SearchResult result : candidates) {
            heap.offer(result);
            if (heap.size() > topK) {
                heap.poll();
            }
        }
        List<SearchResult> finalResults = new ArrayList<>();
        while (!heap.isEmpty()) {
            finalResults.add(heap.poll());
        }
        Collections.reverse(finalResults);
        return finalResults;
    }

    private ClusterState getClusterState() {
        try {
            String json = client.get(clusterCoordinatorUrl + "/state");
            return JsonSerializer.fromJson(json, ClusterState.class);
        } catch (Exception e) {
            System.err.println("Failed to get cluster state from " + clusterCoordinatorUrl + "/state: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private List<NodeInfo> selectTargets(ClusterState state) {
        Map<String, NodeInfo> onlineIndexNodes = new HashMap<>();
        for (NodeInfo node : state.nodes()) {
            if (node.status() == NodeStatus.ONLINE && node.role() == NodeRole.INDEX) {
                onlineIndexNodes.put(node.nodeId(), node);
            }
        }

        // Prefer one node per shard (primary, else first online replica).
        Map<String, NodeInfo> selected = new LinkedHashMap<>();
        for (ShardInfo shard : state.shards()) {
            NodeInfo primary = onlineIndexNodes.get(shard.primaryNodeId());
            if (primary != null) {
                selected.putIfAbsent(shard.primaryNodeId(), primary);
            } else if (shard.replicaNodeIds() != null) {
                for (String replica : shard.replicaNodeIds()) {
                    NodeInfo replicaNode = onlineIndexNodes.get(replica);
                    if (replicaNode != null) {
                        selected.putIfAbsent(replica, replicaNode);
                        break;
                    }
                }
            }
        }

        // Fallback: with no shard metadata, query every online index node.
        if (selected.isEmpty()) {
            return new ArrayList<>(onlineIndexNodes.values());
        }
        return new ArrayList<>(selected.values());
    }
}
