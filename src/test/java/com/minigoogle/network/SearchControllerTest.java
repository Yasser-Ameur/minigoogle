package com.minigoogle.network;

import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.network.api.SearchController;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.serialization.JsonSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for SearchController API functionality. */
class SearchControllerTest {

    private RestServer server;

    @BeforeEach
    void setUp() {
        server = new RestServer(8090);

        // Wire up a simple handler that returns canned results
        new SearchController(server, request -> {
            SearchResult result = new SearchResult(
                    "https://example.com",
                    "Example Title",
                    "...a **" + request.query() + "** result...",
                    0.87, 0.7, 0.17
            );
            return new SearchResponse(12, 1, List.of(result));
        });

        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testSearchEndpoint() {
        RestClient client = new RestClient();
        SearchRequest request = new SearchRequest("compiler", 1, 10);
        String payload = JsonSerializer.toJson(request);

        String responseBody = client.post("http://localhost:8090/api/v1/search", payload);
        SearchResponse response = JsonSerializer.fromJson(responseBody, SearchResponse.class);

        assertEquals(12, response.executionTimeMs());
        assertEquals(1, response.totalResults());
        assertEquals(1, response.results().size());

        SearchResult result = response.results().get(0);
        assertEquals("https://example.com", result.url());
        assertEquals("Example Title", result.title());
        assertTrue(result.snippet().contains("**compiler**"));
        assertEquals(0.87, result.score(), 0.001);
    }
}
