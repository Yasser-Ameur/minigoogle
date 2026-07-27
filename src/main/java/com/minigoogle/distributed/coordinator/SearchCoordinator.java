package com.minigoogle.distributed.coordinator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minigoogle.distributed.balancing.LoadBalancer;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.distributed.model.ShardInfo;
import com.minigoogle.distributed.registry.ClusterState;
import com.minigoogle.ranking.model.RankedDocument;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates distributed search requests using the Scatter-Gather pattern.
 */
public class SearchCoordinator {

    private final RestClient client;
    private final Gson gson;
    private final String clusterCoordinatorUrl;
    private final LoadBalancer loadBalancer;

    public SearchCoordinator(String clusterCoordinatorUrl) {
        this.client = new RestClient();
        this.gson = new GsonBuilder().create();
        this.clusterCoordinatorUrl = clusterCoordinatorUrl;
        this.loadBalancer = new LoadBalancer();
    }

    /**
     * Executes a distributed search query.
     *
     * @param query The user's query string.
     * @param topK  The number of results to return.
     * @return List of globally ranked top K results.
     */
    public List<RankedDocument> search(String query, int topK) {
        // 1. Fetch current cluster state
        ClusterState state = getClusterState();
        if (state == null || state.nodes().isEmpty() || state.shards().isEmpty()) {
            return List.of();
        }

        // 2. Determine target nodes (one replica per shard)
        Map<Integer, String> targetNodesByShard = selectTargets(state);

        // 3. Scatter: Send query to all target nodes asynchronously
        List<CompletableFuture<List<RankedDocument>>> futures = new ArrayList<>();
        SearchRequest request = new SearchRequest(query, topK);
        String requestJson = gson.toJson(request);

        for (String targetUrl : targetNodesByShard.values()) {
            CompletableFuture<List<RankedDocument>> future = client.postAsync(targetUrl + "/query", requestJson)
                    .thenApply(response -> {
                        SearchResponse resp = gson.fromJson(response, SearchResponse.class);
                        return resp != null && resp.results != null ? resp.results : List.<RankedDocument>of();
                    })
                    .exceptionally(ex -> {
                        System.err.println("Shard query failed: " + ex.getMessage());
                        return List.of();
                    });
            futures.add(future);
        }

        // 4. Gather: Wait for all responses
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 5. Merge: Use a max-heap to find the global Top K
        PriorityQueue<RankedDocument> heap = new PriorityQueue<>();
        for (CompletableFuture<List<RankedDocument>> f : futures) {
            try {
                List<RankedDocument> shardResults = f.get();
                for (RankedDocument doc : shardResults) {
                    heap.offer(doc);
                    if (heap.size() > topK) {
                        heap.poll();
                    }
                }
            } catch (Exception e) {
                // Ignore, handled in exceptionally block
            }
        }

        // Extract and sort results (highest score first)
        List<RankedDocument> finalResults = new ArrayList<>();
        while (!heap.isEmpty()) {
            finalResults.add(heap.poll());
        }
        Collections.reverse(finalResults);
        return finalResults;
    }

    private ClusterState getClusterState() {
        try {
            String json = client.get(clusterCoordinatorUrl + "/state");
            return gson.fromJson(json, ClusterState.class);
        } catch (Exception e) {
            System.err.println("Failed to get cluster state: " + e.getMessage());
            return null;
        }
    }

    private Map<Integer, String> selectTargets(ClusterState state) {
        Map<String, NodeInfo> nodeMap = new HashMap<>();
        for (NodeInfo node : state.nodes()) {
            if (node.status() == NodeStatus.ONLINE && node.role() == NodeRole.INDEX) {
                nodeMap.put(node.nodeId(), node);
            }
        }

        Map<Integer, String> targets = new HashMap<>();
        for (ShardInfo shard : state.shards()) {
            List<String> candidates = new ArrayList<>();
            if (nodeMap.containsKey(shard.primaryNodeId())) {
                candidates.add(shard.primaryNodeId());
            }
            if (shard.replicaNodeIds() != null) {
                for (String replica : shard.replicaNodeIds()) {
                    if (nodeMap.containsKey(replica)) {
                        candidates.add(replica);
                    }
                }
            }

            String selectedNodeId = loadBalancer.nextNode(candidates);
            if (selectedNodeId != null) {
                NodeInfo info = nodeMap.get(selectedNodeId);
                targets.put(shard.shardId(), "http://" + info.host() + ":" + info.port());
            }
        }
        return targets;
    }

    // DTOs
    private static class SearchRequest {
        String query;
        int topK;

        public SearchRequest(String query, int topK) {
            this.query = query;
            this.topK = topK;
        }
    }

    private static class SearchResponse {
        List<RankedDocument> results;
    }
}
