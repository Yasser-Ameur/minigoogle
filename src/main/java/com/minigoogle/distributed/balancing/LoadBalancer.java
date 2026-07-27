package com.minigoogle.distributed.balancing;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple round-robin load balancer.
 * Returns the next node ID from a list of candidates in a circular fashion.
 */
public class LoadBalancer {

    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Picks the next node ID from the list using round-robin.
     *
     * @param nodeIds List of candidate node IDs (e.g. primary + replicas).
     * @return The selected node ID, or null if the list is empty.
     */
    public String nextNode(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return null;
        }
        int index = (counter.getAndIncrement() & Integer.MAX_VALUE) % nodeIds.size();
        return nodeIds.get(index);
    }
}
