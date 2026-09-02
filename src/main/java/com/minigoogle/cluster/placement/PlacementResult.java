package com.minigoogle.cluster.placement;

import java.util.List;

/**
 * The outcome of {@link com.minigoogle.cluster.ClusterNode#place}.
 *
 * <p>The caller (the node that crawled the document) always keeps its own
 * local copy outside of placement, so {@code owners} and {@code selfIsOwner}
 * tell it whether that local copy is itself an owner's copy or an extra one,
 * and {@code deliveredTo}/{@code failedTo} report the remote owners placement
 * reached (or could not reach).
 *
 * @param owners      Every node that owns the document, ring order.
 * @param selfIsOwner Whether the local node is one of {@code owners}.
 * @param deliveredTo The remote owners the document was successfully posted to.
 * @param failedTo    The remote owners the document could not be delivered to.
 */
public record PlacementResult(
        List<String> owners,
        boolean selfIsOwner,
        List<String> deliveredTo,
        List<String> failedTo
) {
}
