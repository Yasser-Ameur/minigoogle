package com.minigoogle.cluster.transport;

import com.minigoogle.cluster.GossipProtocol.GossipNodeState;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface MembershipTransport extends ClusterTransport {
    /**
     * Pushes this node's membership table to {@code targetNodeId} and
     * returns the responder's own table (push-pull), so a single exchange
     * merges state in both directions. An implementation whose responder
     * carries no state (an older peer) returns an empty map, not a failure.
     */
    CompletableFuture<Map<String, GossipNodeState>> exchangeState(String targetNodeId, Map<String, GossipNodeState> state);
}
