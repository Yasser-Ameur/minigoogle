package com.minigoogle.distributed.query.execution;

import com.minigoogle.cluster.transport.SearchTransport;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSearchExecutorTimeoutTest {

    @Test
    void testHangingRemoteShardTimesOutWithinBudget() {
        // A remote that never responds
        SearchTransport hungTransport = new SearchTransport() {
            @Override
            public CompletableFuture<LocalSearchResponse> dispatchQuery(String targetNodeId, QueryContext queryContext) {
                return new CompletableFuture<>();
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        };
        RemoteSearchExecutor executor = new RemoteSearchExecutor("hung-node", hungTransport);

        // 100ms budget
        QueryContext context = new QueryContext("query", 10, Duration.ofMillis(100));

        long start = System.currentTimeMillis();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> executor.execute(context));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ex.getCause() instanceof java.util.concurrent.TimeoutException,
                "Expected a TimeoutException cause");
        // Generous bound: must not block forever; well under a typical scatter budget
        assertTrue(elapsed < 2000, "Timed out after " + elapsed + "ms — expected near the 100ms budget");
    }

    @Test
    void testFastRemoteReturnsNormally() {
        SearchTransport fastTransport = new SearchTransport() {
            @Override
            public CompletableFuture<LocalSearchResponse> dispatchQuery(String targetNodeId, QueryContext queryContext) {
                return CompletableFuture.completedFuture(new LocalSearchResponse(0, java.util.List.of(), 0, 1));
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        };
        RemoteSearchExecutor executor = new RemoteSearchExecutor("fast-node", fastTransport);

        QueryContext context = new QueryContext("query", 10, Duration.ofMillis(1000));
        LocalSearchResponse response = executor.execute(context);

        assertNotNull(response);
    }
}
