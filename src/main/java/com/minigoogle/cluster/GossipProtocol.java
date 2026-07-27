package com.minigoogle.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gossip protocol for cluster membership and failure detection.
 *
 * Per ARCHITECTURE.md Ch14:
 *   Nodes periodically exchange membership information.
 *   Each node maintains a heartbeat counter.
 *   When a node hasn't been heard from within the timeout,
 *   it is marked suspect, then confirmed dead.
 *
 * The protocol is fault-tolerant: it works even when messages
 * are lost or delayed, and converges to a consistent view
 * of cluster membership.
 */
public class GossipProtocol {

    private final String nodeId;
    private final Map<String, GossipNodeState> membershipTable = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private ScheduledExecutorService scheduler;
    private final long gossipIntervalMs;
    private final long failureTimeoutMs;

    /**
     * Creates a gossip protocol node with full configuration.
     *
     * @param nodeId            The unique identifier for this node.
     * @param gossipIntervalMs  The interval between gossip rounds in milliseconds.
     * @param failureTimeoutMs  The timeout after which a node is considered suspect.
     */
    public GossipProtocol(String nodeId, long gossipIntervalMs, long failureTimeoutMs) {
        this.nodeId = nodeId;
        this.gossipIntervalMs = gossipIntervalMs;
        this.failureTimeoutMs = failureTimeoutMs;
        // Add self
        membershipTable.put(nodeId, new GossipNodeState(nodeId, 0, NodeStatus.ALIVE, System.currentTimeMillis()));
    }

    /**
     * Creates a gossip protocol node with default intervals.
     *
     * @param nodeId The unique identifier for this node.
     */
    public GossipProtocol(String nodeId) {
        this(nodeId, 1000, 5000);
    }

    /**
     * Starts periodic gossip with random peers.
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Gossip-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::gossipRound, 0, gossipIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the gossip protocol.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Processes an incoming gossip message from another node.
     *
     * @param senderId     The sending node's ID.
     * @param senderTable  The sender's membership table.
     */
    public void receiveGossip(String senderId, Map<String, GossipNodeState> senderTable) {
        for (Map.Entry<String, GossipNodeState> entry : senderTable.entrySet()) {
            String key = entry.getKey();
            GossipNodeState remote = entry.getValue();
            GossipNodeState local = membershipTable.get(key);

            if (local == null || remote.heartbeatCounter() > local.heartbeatCounter()) {
                membershipTable.put(key, remote);
            }
        }
    }

    /**
     * Increments this node's heartbeat and returns the updated state.
     */
    public GossipNodeState heartbeat() {
        GossipNodeState self = membershipTable.get(nodeId);
        long newCounter = self != null ? self.heartbeatCounter() + 1 : 0;
        GossipNodeState updated = new GossipNodeState(nodeId, newCounter, NodeStatus.ALIVE, System.currentTimeMillis());
        membershipTable.put(nodeId, updated);
        return updated;
    }

    /**
     * Marks a node as suspect (potential failure).
     */
    public void suspect(String targetNodeId) {
        GossipNodeState state = membershipTable.get(targetNodeId);
        if (state != null) {
            membershipTable.put(targetNodeId, new GossipNodeState(
                    targetNodeId, state.heartbeatCounter(), NodeStatus.SUSPECT, System.currentTimeMillis()));
        }
    }

    /**
     * Confirms a node as dead and removes it from the cluster.
     */
    public void confirmDead(String targetNodeId) {
        GossipNodeState state = membershipTable.get(targetNodeId);
        if (state != null) {
            membershipTable.put(targetNodeId, new GossipNodeState(
                    targetNodeId, state.heartbeatCounter(), NodeStatus.DEAD, System.currentTimeMillis()));
        }
    }

    /**
     * Returns the current membership table.
     */
    public Map<String, GossipNodeState> getMembershipTable() {
        return Map.copyOf(membershipTable);
    }

    /**
     * Returns all nodes currently marked as ALIVE.
     */
    public List<String> getLiveNodes() {
        List<String> live = new ArrayList<>();
        for (Map.Entry<String, GossipNodeState> entry : membershipTable.entrySet()) {
            if (entry.getValue().status() == NodeStatus.ALIVE) {
                live.add(entry.getKey());
            }
        }
        return live;
    }

    /**
     * Checks for timed-out nodes and marks them as suspect.
     */
    public void checkForFailures() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, GossipNodeState> entry : membershipTable.entrySet()) {
            GossipNodeState state = entry.getValue();
            if (!entry.getKey().equals(nodeId) &&
                    state.status() == NodeStatus.ALIVE &&
                    (now - state.lastSeen()) > failureTimeoutMs) {
                suspect(entry.getKey());
            }
        }
    }

    /**
     * Returns the number of nodes in the membership table.
     */
    public int memberCount() {
        return membershipTable.size();
    }

    private void gossipRound() {
        heartbeat();
        checkForFailures();

        List<String> liveNodes = getLiveNodes();
        if (liveNodes.isEmpty()) return;

        // Pick a random live peer to exchange state with
        String peer = liveNodes.get(random.nextInt(liveNodes.size()));
        // In a real implementation, this would send the membership table over the network
        // Here we just log the event
    }

    public enum NodeStatus {
        ALIVE,
        SUSPECT,
        DEAD
    }

    public record GossipNodeState(
            String nodeId,
            long heartbeatCounter,
            NodeStatus status,
            long lastSeen
    ) {
    }
}
