package com.minigoogle.network.client;

import com.minigoogle.network.http.RestClient;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.registry.ClusterState;
import com.minigoogle.network.retry.RetryPolicy;
import com.minigoogle.network.serialization.JsonSerializer;

/**
 * Typed client for communicating with the Cluster API.
 * Handles registration, heartbeats, and state queries with retries.
 */
public class ClusterClient {

    private final RestClient restClient;
    private final RetryPolicy retryPolicy;

    public ClusterClient(RestClient restClient, RetryPolicy retryPolicy) {
        this.restClient = restClient;
        this.retryPolicy = retryPolicy;
    }

    public ClusterClient(RestClient restClient) {
        this(restClient, new RetryPolicy());
    }

    /**
     * Registers a node with the cluster coordinator.
     */
    public void register(String coordinatorUrl, NodeInfo nodeInfo) throws Exception {
        String payload = JsonSerializer.toJson(nodeInfo);
        retryPolicy.execute(() ->
                restClient.post(coordinatorUrl + "/api/v1/cluster/register", payload));
    }

    /**
     * Sends a heartbeat to the cluster coordinator.
     */
    public void heartbeat(String coordinatorUrl, String nodeId) throws Exception {
        String payload = String.format("{\"nodeId\":\"%s\"}", nodeId);
        retryPolicy.execute(() ->
                restClient.post(coordinatorUrl + "/api/v1/cluster/heartbeat", payload));
    }

    /**
     * Fetches the current cluster state.
     */
    public ClusterState getState(String coordinatorUrl) throws Exception {
        String responseBody = retryPolicy.execute(() ->
                restClient.get(coordinatorUrl + "/api/v1/cluster/state"));
        return JsonSerializer.fromJson(responseBody, ClusterState.class);
    }
}
