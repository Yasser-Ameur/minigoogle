package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.dto.DispatchQueryRequest;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.minigoogle.network.dto.SearchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpSearchTransportTest {
    private HttpServer server;
    private int port;
    private ObjectMapper mapper;
    private SearchExecutor local;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        mapper = new ObjectMapper();
        local = context -> new LocalSearchResponse(
                7,
                List.of(new SearchResult("http://shard/doc", "Doc", "snippet", 0.95, 0.8, 0.15)),
                128,
                5);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private NodeDirectory directoryFor(String nodeId) {
        return target -> target.equals(nodeId) ? URI.create("http://localhost:" + port) : null;
    }

    @Test
    void testDispatchQueryRoundTrip() throws Exception {
        server.createContext("/cluster/v1/search/dispatch", new SearchHandler(local, mapper, "node-2"));

        HttpSearchTransport transport = new HttpSearchTransport(directoryFor("node-2"), mapper, "node-1");
        QueryContext context = new QueryContext("distributed systems", 10, Duration.ofSeconds(5));

        LocalSearchResponse response = transport.dispatchQuery("node-2", context)
                .get(5, TimeUnit.SECONDS);

        assertNotNull(response);
        assertEquals(7, response.shardId());
        assertEquals(128, response.totalHits());
        assertEquals(1, response.results().size());
        assertEquals("http://shard/doc", response.results().get(0).url());
    }

    @Test
    void testWireRequestCarriesQueryAndBudget() throws Exception {
        final DispatchQueryRequest[] captured = new DispatchQueryRequest[1];
        server.createContext("/cluster/v1/search/dispatch", exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                captured[0] = mapper.readValue(is, DispatchQueryRequest.class);
            }
            byte[] body = mapper.writeValueAsBytes(new LocalSearchResponse(7, List.of(), 0, 0));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });

        HttpSearchTransport transport = new HttpSearchTransport(directoryFor("node-2"), mapper, "node-1");
        QueryContext context = new QueryContext("the query", 5, Duration.ofSeconds(3));

        transport.dispatchQuery("node-2", context).get(5, TimeUnit.SECONDS);

        assertNotNull(captured[0]);
        assertEquals("node-1", captured[0].sourceNodeId());
        assertEquals("the query", captured[0].query());
        assertEquals(5, captured[0].topK());
        assertTrue(captured[0].remainingTimeMs() > 0);
        assertEquals(context.getRequestId().toString(), captured[0].requestId());
    }

    @Test
    void testErrorStatusFailsFuture() throws Exception {
        server.createContext("/cluster/v1/search/dispatch", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        HttpSearchTransport transport = new HttpSearchTransport(directoryFor("node-2"), mapper, "node-1");
        QueryContext context = new QueryContext("query", 10, Duration.ofSeconds(5));

        Exception ex = assertThrows(Exception.class,
                () -> transport.dispatchQuery("node-2", context).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("HTTP Error"));
    }

    @Test
    void testUnknownNodeFailsFuture() {
        HttpSearchTransport transport = new HttpSearchTransport(directoryFor("node-2"), mapper, "node-1");
        QueryContext context = new QueryContext("query", 10, Duration.ofSeconds(5));

        Exception ex = assertThrows(Exception.class,
                () -> transport.dispatchQuery("unknown-node", context).get(5, TimeUnit.SECONDS));
        assertTrue(ex.getMessage().contains("Unknown node"));
    }
}
