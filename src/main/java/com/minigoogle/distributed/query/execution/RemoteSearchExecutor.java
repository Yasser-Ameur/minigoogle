package com.minigoogle.distributed.query.execution;

import com.minigoogle.cluster.transport.SearchTransport;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a search query by dispatching it to a remote node via SearchTransport.
 * Implements SearchExecutor to allow DistributedExecutor to treat remote and local shards uniformly.
 *
 * <p>The blocking wait is bounded by the QueryContext's remaining time budget so
 * that a hung remote shard cannot outlive the scatter deadline.
 */
public class RemoteSearchExecutor implements SearchExecutor {

    private final String targetNodeId;
    private final SearchTransport transport;

    public RemoteSearchExecutor(String targetNodeId, SearchTransport transport) {
        this.targetNodeId = targetNodeId;
        this.transport = transport;
    }

    @Override
    public LocalSearchResponse execute(QueryContext context) {
        try {
            return transport.dispatchQuery(targetNodeId, context)
                    .get(context.getRemainingTimeMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Remote execution to " + targetNodeId + " timed out after "
                    + context.getRemainingTimeMs() + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Remote execution to " + targetNodeId + " interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed remote execution to " + targetNodeId, e);
        }
    }
}
