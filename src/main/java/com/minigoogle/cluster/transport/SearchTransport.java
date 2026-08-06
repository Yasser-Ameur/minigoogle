package com.minigoogle.cluster.transport;

import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;

import java.util.concurrent.CompletableFuture;

public interface SearchTransport extends ClusterTransport {
    CompletableFuture<LocalSearchResponse> dispatchQuery(String targetNodeId, QueryContext queryContext);
}
