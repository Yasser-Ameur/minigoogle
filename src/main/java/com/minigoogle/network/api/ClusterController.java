package com.minigoogle.network.api;

import com.minigoogle.network.http.RestServer;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.registry.NodeRegistry;
import com.minigoogle.network.dto.ErrorResponse;
import com.minigoogle.network.serialization.JsonSerializer;

/**
 * Controller exposing /api/v1/cluster/* endpoints.
 */
public class ClusterController {

    private final RestServer server;
    private final NodeRegistry registry;

    public ClusterController(RestServer server, NodeRegistry registry) {
        this.server = server;
        this.registry = registry;
        setupRoutes();
    }

    private void setupRoutes() {
        server.post("/api/v1/cluster/register", body -> {
            try {
                NodeInfo node = JsonSerializer.fromJson(body, NodeInfo.class);
                if (node == null || node.nodeId() == null) {
                    return JsonSerializer.toJson(new ErrorResponse("INVALID_REQUEST", "NodeInfo or nodeId is null"));
                }
                registry.register(node);
                return "{\"status\":\"SUCCESS\"}";
            } catch (Exception e) {
                return JsonSerializer.toJson(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
            }
        });

        server.post("/api/v1/cluster/heartbeat", body -> {
            try {
                HeartbeatRequest req = JsonSerializer.fromJson(body, HeartbeatRequest.class);
                if (req != null && req.nodeId != null) {
                    registry.heartbeat(req.nodeId);
                }
                return "{\"status\":\"OK\"}";
            } catch (Exception e) {
                return JsonSerializer.toJson(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
            }
        });

        server.get("/api/v1/cluster/state", body -> {
            try {
                return JsonSerializer.toJson(registry.getState());
            } catch (Exception e) {
                return JsonSerializer.toJson(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
            }
        });
    }

    private static class HeartbeatRequest {
        String nodeId;
        double cpuUsage;
        double memoryUsage;
    }
}
