package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.ClusterSecurity;
import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.dto.DispatchQueryRequest;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.minigoogle.network.dto.SearchResult;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SearchHandlerTest {
    private HttpServer server;
    private int port;
    private ObjectMapper mapper;
    private HttpClient client;
    private ClusterSecurity security;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        security = new ClusterSecurity("test-secret");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpContext protectContext(String path, SearchHandler handler) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(new AuthFilter(security));
        return context;
    }

    private HttpResponse<String> post(String path, Object req) throws Exception {
        String payload = mapper.writeValueAsString(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(HttpAuth.AUTHORIZATION, HttpAuth.bearer(security.deriveToken("coordinator-1")))
                .header(HttpAuth.NODE_ID, "coordinator-1")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private DispatchQueryRequest dispatchRequest(int version, String query) {
        return new DispatchQueryRequest(
                version, UUID.randomUUID().toString(), "corr-1", "coordinator-1", 0L, query, 10, 5000);
    }

    @Test
    void testHappyPathRunsQueryAndReturnsTopK() throws Exception {
        SearchExecutor local = context -> new LocalSearchResponse(
                2,
                List.of(new SearchResult("http://shard2/doc", "Doc", "snippet", 0.9, 0.8, 0.1)),
                42,
                3);
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(local, mapper, "node-2"));

        HttpResponse<String> resp = post("/cluster/v1/search/dispatch", dispatchRequest(1, "distributed systems"));

        assertEquals(200, resp.statusCode());
        LocalSearchResponse body = mapper.readValue(resp.body(), LocalSearchResponse.class);
        assertEquals(2, body.shardId());
        assertEquals(42, body.totalHits());
        assertEquals(1, body.results().size());
        assertEquals("http://shard2/doc", body.results().get(0).url());
    }

    @Test
    void testPreservesCoordinatorRequestId() throws Exception {
        final QueryContext[] captured = new QueryContext[1];
        SearchExecutor local = context -> {
            captured[0] = context;
            return new LocalSearchResponse(1, List.of(), 0, 0);
        };
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(local, mapper, "node-2"));

        DispatchQueryRequest req = dispatchRequest(1, "query");
        post("/cluster/v1/search/dispatch", req);

        assertNotNull(captured[0]);
        assertEquals(req.requestId(), captured[0].getRequestId().toString());
        assertEquals("query", captured[0].getQuery());
        assertEquals(10, captured[0].getTopK());
    }

    @Test
    void testGetMethodNotAllowed() throws Exception {
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(null, mapper, "node-2"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cluster/v1/search/dispatch"))
                .header(HttpAuth.AUTHORIZATION, HttpAuth.bearer(security.deriveToken("coordinator-1")))
                .header(HttpAuth.NODE_ID, "coordinator-1")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    @Test
    void testUnsupportedVersionRejected() throws Exception {
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(null, mapper, "node-2"));
        HttpResponse<String> resp = post("/cluster/v1/search/dispatch",
                dispatchRequest(ClusterProtocol.PROTOCOL_VERSION + 1, "query"));
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testNoLocalSearchReturnsServiceUnavailable() throws Exception {
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(null, mapper, "node-2"));
        HttpResponse<String> resp = post("/cluster/v1/search/dispatch", dispatchRequest(1, "query"));
        assertEquals(503, resp.statusCode());
    }

    @Test
    void testUnauthenticatedDispatchRejected() throws Exception {
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(null, mapper, "node-2"));

        String payload = mapper.writeValueAsString(dispatchRequest(1, "query"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cluster/v1/search/dispatch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, resp.statusCode());
    }

    @Test
    void testSourceNodeMismatchRejected() throws Exception {
        protectContext("/cluster/v1/search/dispatch", new SearchHandler(null, mapper, "node-2"));

        String payload = mapper.writeValueAsString(dispatchRequest(1, "query"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/cluster/v1/search/dispatch"))
                .header("Content-Type", "application/json")
                .header(HttpAuth.AUTHORIZATION, HttpAuth.bearer(security.deriveToken("node-2")))
                .header(HttpAuth.NODE_ID, "node-2")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode());
    }
}
