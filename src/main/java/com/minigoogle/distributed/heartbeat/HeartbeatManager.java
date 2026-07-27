package com.minigoogle.distributed.heartbeat;

import com.minigoogle.network.http.RestClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically sends heartbeat messages to the ClusterCoordinator
 * to indicate this node is alive.
 */
public class HeartbeatManager {

    private final String coordinatorUrl;
    private final String nodeId;
    private final RestClient restClient;
    private final ScheduledExecutorService scheduler;

    public HeartbeatManager(String coordinatorUrl, String nodeId, RestClient restClient) {
        this.coordinatorUrl = coordinatorUrl;
        this.nodeId = nodeId;
        this.restClient = restClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatManager-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts sending heartbeats every intervalMillis.
     */
    public void start(long intervalMillis) {
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void sendHeartbeat() {
        try {
            // A simple JSON payload for the heartbeat
            String payload = String.format("{\"nodeId\":\"%s\"}", nodeId);
            restClient.post(coordinatorUrl + "/heartbeat", payload);
        } catch (Exception e) {
            System.err.println("Failed to send heartbeat to coordinator: " + e.getMessage());
        }
    }
}
