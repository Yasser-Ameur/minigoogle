package com.minigoogle.cluster.placement;

/**
 * Enumerates the documents already indexed on this node, so
 * {@link PlacementRepairListener} can re-check their ownership after a
 * membership change and repair any owner that is missing a copy.
 *
 * <p>Implemented by the composition root over the node's own crawl store.
 */
public interface LocalDocuments {

    Iterable<IngestedDocument> all();
}
