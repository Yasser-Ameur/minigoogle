package com.minigoogle.distributed.coordinator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.ShardInfo;
import com.minigoogle.distributed.registry.ClusterState;
import com.minigoogle.distributed.registry.NodeRegistry;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The master node of the cluster.
 * Maintains the NodeRegistry, receives heartbeats, and tracks cluster health.
 */
public class ClusterCoordinator {

    private final NodeRegistry registry;
    private final RestServer server;
    private final Gson gson;
    private final AtomicInteger nextShardId = new AtomicInteger(0);
    private final ScheduledExecutorService healthChecker;

    public ClusterCoordinator(int port) {
        this.registry = new NodeRegistry();
        this.server = new RestServer(port);
        this.gson = new GsonBuilder().create();

        // Background thread to check node health
        this.healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClusterHealthChecker");
            t.setDaemon(true);
            return t;
        });

        setupRoutes();
    }

    private void setupRoutes() {
        // Register a new node
        server.post("/register", body -> {
            NodeInfo node = gson.fromJson(body, NodeInfo.class);
            registry.register(node);
            // Auto-assign a shard to each index node that doesn't own one yet,
            // so scatter-gather has concrete shard targets to query.
            if (node.role() == NodeRole.INDEX && registry.getShards().stream()
                    .noneMatch(s -> s.primaryNodeId().equals(node.nodeId()))) {
                int shardId = nextShardId.incrementAndGet();
                registry.registerShard(new ShardInfo(shardId, node.nodeId(), List.of()));
            }
            return "{\"status\":\"REGISTERED\"}";
        });

        // Receive heartbeat
        server.post("/heartbeat", body -> {
            HeartbeatRequest req = gson.fromJson(body, HeartbeatRequest.class);
            if (req != null && req.nodeId != null) {
                registry.heartbeat(req.nodeId);
            }
            return "{\"status\":\"OK\"}";
        });

        // Get cluster state
        server.get("/state", body -> gson.toJson(registry.getState()));
    }

    public void start() {
        server.start();
        healthChecker.scheduleAtFixedRate(registry::checkHealth, 5, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        healthChecker.shutdownNow();
        server.stop();
    }

    /**
     * Returns the current cluster state (nodes + shards).
     */
    public ClusterState getState() {
        return registry.getState();
    }

    /**
     * Returns the port the cluster registry is bound to.
     */
    public int getPort() {
        return server.getPort();
    }

    // DTO for heartbeat
    private static class HeartbeatRequest {
        String nodeId;
    }
}
