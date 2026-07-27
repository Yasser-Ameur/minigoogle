package com.minigoogle.distributed.coordinator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.registry.NodeRegistry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The master node of the cluster.
 * Maintains the NodeRegistry, receives heartbeats, and tracks cluster health.
 */
public class ClusterCoordinator {

    private final NodeRegistry registry;
    private final RestServer server;
    private final Gson gson;
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

    // DTO for heartbeat
    private static class HeartbeatRequest {
        String nodeId;
    }
}
