package com.minigoogle.distributed.query.routing;

import java.util.List;

/**
 * Interface for routing search queries to appropriate cluster nodes.
 */
public interface QueryRouter {
    /**
     * Resolves the set of node IDs that should handle this query.
     * For keyword searches, this typically returns all nodes hosting shards.
     * For document lookups, this returns the specific owner node.
     *
     * @param query the search query string
     * @return list of target node IDs (never null, may be empty)
     */
    List<String> resolveTargets(String query);
}
