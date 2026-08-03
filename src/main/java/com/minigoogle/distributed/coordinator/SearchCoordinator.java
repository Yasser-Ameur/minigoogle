package com.minigoogle.distributed.coordinator;

import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.distributed.model.ShardInfo;
import com.minigoogle.distributed.registry.ClusterState;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.serialization.JsonSerializer;

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
 * Fetches the current cluster state from the ClusterCoordinator, fans the
 * query out to every online index node over the standard {@code POST
 * /api/v1/search} protocol, then merges the per-node results into a global
 * top-K ranked list.
 */
public class SearchCoordinator {

    private final RestClient client;
    private final String clusterCoordinatorUrl;

    public SearchCoordinator(String clusterCoordinatorUrl) {
        this.client = new RestClient();
        this.clusterCoordinatorUrl = clusterCoordinatorUrl;
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

        // 3. Scatter: Send query to all target nodes asynchronously
        String requestJson = JsonSerializer.toJson(new SearchRequest(query, 1, topK));
        List<CompletableFuture<List<SearchResult>>> futures = new ArrayList<>();

        for (NodeInfo node : targets) {
            String url = "http://" + node.host() + ":" + node.port() + "/api/v1/search";
            CompletableFuture<List<SearchResult>> future = client.postAsync(url, requestJson)
                    .thenApply(body -> {
                        SearchResponse resp = JsonSerializer.fromJson(body, SearchResponse.class);
                        return resp != null && resp.results() != null
                                ? resp.results()
                                : List.<SearchResult>of();
                    })
                    .exceptionally(ex -> {
                        System.err.println("Shard query failed: " + ex.getMessage());
                        return List.of();
                    });
            futures.add(future);
        }

        // 4. Gather: Wait for all responses
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 5. Merge: Use a min-heap to find the global Top K
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(SearchResult::score));
        for (CompletableFuture<List<SearchResult>> f : futures) {
            try {
                for (SearchResult result : f.get()) {
                    heap.offer(result);
                    if (heap.size() > topK) {
                        heap.poll();
                    }
                }
            } catch (Exception ignored) {
                // Failures are already handled in the exceptionally block
            }
        }

        // Extract and sort results (highest score first)
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
