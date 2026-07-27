package com.minigoogle.distributed.model;

/**
 * Enum of cluster node roles indicating the functional responsibility of each node.
 */
public enum NodeRole {
    INDEX,
    CRAWLER,
    SEARCH_COORDINATOR,
    CRAWL_COORDINATOR,
    CLUSTER_COORDINATOR
}
