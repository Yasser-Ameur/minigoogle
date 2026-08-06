package com.minigoogle.cluster.transport.dto;

import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.transport.ClusterMessage;

import java.util.Map;

public record GossipExchangeRequest(
        int protocolVersion,
        String requestId,
        String correlationId,
        String sourceNodeId,
        long timestamp,
        Map<String, GossipNodeState> state
) implements ClusterMessage {
}
