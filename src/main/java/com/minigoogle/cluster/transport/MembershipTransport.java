package com.minigoogle.cluster.transport;

import com.minigoogle.cluster.GossipProtocol.GossipNodeState;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface MembershipTransport extends ClusterTransport {
    CompletableFuture<Void> exchangeState(String targetNodeId, Map<String, GossipNodeState> state);
}
