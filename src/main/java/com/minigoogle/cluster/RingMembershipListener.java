package com.minigoogle.cluster;

import java.util.logging.Logger;

/**
 * Bridges gossip membership events to the consistent hash ring.
 *
 * When gossip detects a node joining or leaving, this listener
 * adds or removes the node from the ring, ensuring routing tables
 * stay consistent with cluster membership.
 */
public class RingMembershipListener implements MembershipListener {

    private static final Logger logger = Logger.getLogger(RingMembershipListener.class.getName());
    private final ConsistentHashRing ring;

    public RingMembershipListener(ConsistentHashRing ring) {
        this.ring = ring;
    }

    @Override
    public void onNodeJoined(String nodeId) {
        ring.addNode(nodeId);
        logger.info("Ring: added node " + nodeId + " (ring size: " + ring.nodeCount() + ")");
    }

    @Override
    public void onNodeLeft(String nodeId) {
        ring.removeNode(nodeId);
        logger.info("Ring: removed node " + nodeId + " (ring size: " + ring.nodeCount() + ")");
    }
}
