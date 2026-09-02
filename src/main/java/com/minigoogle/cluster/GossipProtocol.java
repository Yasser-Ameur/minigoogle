package com.minigoogle.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.minigoogle.cluster.transport.MembershipTransport;

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
    private final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    private final Random random = new Random();
    private ScheduledExecutorService scheduler;
    private final long gossipIntervalMs;
    private final long failureTimeoutMs;
    private final long deadTimeoutMs;

    private final MembershipTransport transport;

    /**
     * Creates a gossip protocol node with full configuration, including the
     * SUSPECT to DEAD escalation timeout.
     *
     * @param nodeId            The unique identifier for this node.
     * @param gossipIntervalMs  The interval between gossip rounds in milliseconds.
     * @param failureTimeoutMs  The timeout after which a node is considered suspect.
     * @param deadTimeoutMs     The timeout after which a SUSPECT node is confirmed DEAD.
     * @param transport         The transport layer for cluster communication.
     */
    public GossipProtocol(String nodeId, long gossipIntervalMs, long failureTimeoutMs, long deadTimeoutMs,
                           MembershipTransport transport) {
        this.nodeId = nodeId;
        this.gossipIntervalMs = gossipIntervalMs;
        this.failureTimeoutMs = failureTimeoutMs;
        this.deadTimeoutMs = deadTimeoutMs;
        this.transport = transport;
        // Add self
        // The counter starts at the wall clock so a restarted node outranks every
        // frozen copy of its previous life; survivors then accept its ALIVE state
        // from third parties by the ordinary fresher-counter rule.
        membershipTable.put(nodeId, new GossipNodeState(nodeId, System.currentTimeMillis(), NodeStatus.ALIVE, System.currentTimeMillis()));
    }

    /**
     * Creates a gossip protocol node with full configuration.
     *
     * @param nodeId            The unique identifier for this node.
     * @param gossipIntervalMs  The interval between gossip rounds in milliseconds.
     * @param failureTimeoutMs  The timeout after which a node is considered suspect.
     * @param transport         The transport layer for cluster communication.
     */
    public GossipProtocol(String nodeId, long gossipIntervalMs, long failureTimeoutMs, MembershipTransport transport) {
        this(nodeId, gossipIntervalMs, failureTimeoutMs, 3 * failureTimeoutMs, transport);
    }

    /**
     * Creates a gossip protocol node with default intervals and transport.
     *
     * @param nodeId    The unique identifier for this node.
     * @param transport The transport layer for cluster communication.
     */
    public GossipProtocol(String nodeId, MembershipTransport transport) {
        this(nodeId, 1000, 5000, transport);
    }

    /**
     * Legacy constructor for tests without transport.
     */
    public GossipProtocol(String nodeId) {
        this(nodeId, 1000, 5000, null);
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
     * Registers a listener to be notified of membership changes.
     *
     * @param listener The listener to add.
     */
    public void addListener(MembershipListener listener) {
        listeners.add(listener);
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

            if (local == null) {
                // New node discovered
                membershipTable.put(key, remote);
                if (remote.status() == NodeStatus.ALIVE) {
                    for (MembershipListener l : listeners) {
                        l.onNodeJoined(key);
                    }
                }
        } else if (senderId.equals(key)
                || remote.heartbeatCounter() > local.heartbeatCounter()) {
            // A node that contacts us is alive right now whatever counter it
            // carries. Anyone else's entry is taken only when it is fresher: a
            // survivor's frozen ALIVE copy of a stopped peer must not revive
            // that peer on every exchange, or SUSPECT never reaches DEAD. A
            // restarted node outranks its old copies because its counter
            // starts at the wall clock. Never regress the highest-known counter
            // or liveness freshness.
            // The sender is talking to us right now, so its own entry is ALIVE
            // whatever its table says: a stale or hostile self-report must not
            // demote a peer we can plainly reach.
            NodeStatus status = senderId.equals(key) ? NodeStatus.ALIVE : remote.status();
            GossipNodeState updated = new GossipNodeState(
                    key,
                    Math.max(remote.heartbeatCounter(), local.heartbeatCounter()),
                    status,
                    Math.max(remote.lastSeen(), local.lastSeen()));
            membershipTable.put(key, updated);
            // Transition: was not ALIVE, now ALIVE => joined
            if (local.status() != NodeStatus.ALIVE && status == NodeStatus.ALIVE) {
                for (MembershipListener l : listeners) {
                    l.onNodeJoined(key);
                }
            }
        }
        }
    }

    /**
     * Increments this node's heartbeat and returns the updated state.
     */
    public GossipNodeState heartbeat() {
        GossipNodeState self = membershipTable.get(nodeId);
        long newCounter = self != null ? self.heartbeatCounter() + 1 : System.currentTimeMillis();
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
            // lastSeen stays the last real contact: the DEAD clock measures
            // silence since then, not since the suspicion.
            membershipTable.put(targetNodeId, new GossipNodeState(
                    targetNodeId, state.heartbeatCounter(), NodeStatus.SUSPECT, state.lastSeen()));
        }
    }

    /**
     * Confirms a node as dead. The entry stays in the table (never removed)
     * so a later exchange from the node, or a higher heartbeat counter for
     * it, can revive it to ALIVE. Fires {@code onNodeLeft} exactly once per
     * SUSPECT-to-DEAD transition.
     */
    public void confirmDead(String targetNodeId) {
        GossipNodeState state = membershipTable.get(targetNodeId);
        if (state != null && state.status() != NodeStatus.DEAD) {
            membershipTable.put(targetNodeId, new GossipNodeState(
                    targetNodeId, state.heartbeatCounter(), NodeStatus.DEAD, System.currentTimeMillis()));
            for (MembershipListener l : listeners) {
                l.onNodeLeft(targetNodeId);
            }
        }
    }

    /**
     * Returns the current membership table.
     */
    public Map<String, GossipNodeState> getMembershipTable() {
        return Map.copyOf(membershipTable);
    }

    /**
     * Seeds a peer into the membership table for bootstrap discovery.
     * This is how a node learns about initial cluster members before gossip converges.
     *
     * @param peerId The node ID of the seed peer.
     */
    public void seedPeer(String peerId) {
        if (membershipTable.putIfAbsent(peerId, new GossipNodeState(peerId, 0, NodeStatus.ALIVE, System.currentTimeMillis())) == null) {
            for (MembershipListener l : listeners) {
                l.onNodeJoined(peerId);
            }
        }
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
     * Checks for timed-out nodes: an ALIVE node silent past
     * {@code failureTimeoutMs} is marked SUSPECT, and a SUSPECT node silent
     * past {@code deadTimeoutMs} is escalated to DEAD.
     */
    public void checkForFailures() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, GossipNodeState> entry : membershipTable.entrySet()) {
            GossipNodeState state = entry.getValue();
            if (entry.getKey().equals(nodeId)) {
                continue;
            }
            if (state.status() == NodeStatus.ALIVE && (now - state.lastSeen()) > failureTimeoutMs) {
                suspect(entry.getKey());
            } else if (state.status() == NodeStatus.SUSPECT && (now - state.lastSeen()) > deadTimeoutMs) {
                confirmDead(entry.getKey());
            }
        }
    }

    /**
     * Returns all nodes currently marked as SUSPECT.
     */
    public List<String> getSuspectNodes() {
        List<String> suspects = new ArrayList<>();
        for (Map.Entry<String, GossipNodeState> entry : membershipTable.entrySet()) {
            if (entry.getValue().status() == NodeStatus.SUSPECT) {
                suspects.add(entry.getKey());
            }
        }
        return suspects;
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

        if (transport == null) {
            return;
        }

        List<String> peers = getLiveNodes();
        peers.remove(nodeId);
        if (!peers.isEmpty()) {
            // Pick a random live peer to exchange state with
            exchangeWith(peers.get(random.nextInt(peers.size())));
        }

        // Also probe a random SUSPECT peer each round, so a peer that has
        // recovered is revived within one round rather than waiting for it
        // to gossip to us first.
        List<String> suspects = getSuspectNodes();
        if (!suspects.isEmpty()) {
            exchangeWith(suspects.get(random.nextInt(suspects.size())));
        }
    }

    private void exchangeWith(String peer) {
        transport.exchangeState(peer, Map.copyOf(membershipTable))
                .thenAccept(responderStates -> {
                    // The peer answered, so it is alive right now: refresh
                    // its liveness so a bootstrapping/rejoining node does
                    // not fail-detect the very seed it is converging with.
                    GossipNodeState current = membershipTable.get(peer);
                    if (current != null) {
                        boolean rejoined = current.status() != NodeStatus.ALIVE;
                        membershipTable.put(peer, new GossipNodeState(
                                peer, current.heartbeatCounter(), NodeStatus.ALIVE, System.currentTimeMillis()));
                        if (rejoined) {
                            for (MembershipListener l : listeners) {
                                l.onNodeJoined(peer);
                            }
                        }
                    }
                    // Push-pull: merge the responder's own view back in, so
                    // membership converges in one exchange rather than
                    // waiting on the responder's own next round.
                    if (responderStates != null && !responderStates.isEmpty()) {
                        receiveGossip(peer, responderStates);
                    }
                })
                .exceptionally(ex -> {
                    // On failure, rely on normal failure detection
                    return null;
                });
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
