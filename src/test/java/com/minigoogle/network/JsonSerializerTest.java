package com.minigoogle.network;

import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.network.dto.ErrorResponse;
import com.minigoogle.network.serialization.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for JsonSerializer (DTO serialization/deserialization) functionality. */
class JsonSerializerTest {

    @Test
    void testSearchRequestRoundTrip() {
        SearchRequest request = new SearchRequest("distributed systems", 1, 20);
        String json = JsonSerializer.toJson(request);
        SearchRequest deserialized = JsonSerializer.fromJson(json, SearchRequest.class);

        assertEquals("distributed systems", deserialized.query());
        assertEquals(1, deserialized.page());
        assertEquals(20, deserialized.pageSize());
    }

    @Test
    void testSearchResponseRoundTrip() {
        SearchResult result = new SearchResult(
                "https://example.com", "Example", "...a **distributed** search...", 0.95, 0.8, 0.15);
        SearchResponse response = new SearchResponse(18, 42781, List.of(result));

        String json = JsonSerializer.toJson(response);
        SearchResponse deserialized = JsonSerializer.fromJson(json, SearchResponse.class);

        assertEquals(18, deserialized.executionTimeMs());
        assertEquals(42781, deserialized.totalResults());
        assertEquals(1, deserialized.results().size());
        assertEquals("https://example.com", deserialized.results().get(0).url());
        assertEquals(0.95, deserialized.results().get(0).score(), 0.001);
    }

    @Test
    void testErrorResponseRoundTrip() {
        ErrorResponse error = new ErrorResponse("INVALID_QUERY", "Query cannot be empty");
        String json = JsonSerializer.toJson(error);
        ErrorResponse deserialized = JsonSerializer.fromJson(json, ErrorResponse.class);

        assertEquals("INVALID_QUERY", deserialized.error());
        assertEquals("Query cannot be empty", deserialized.message());
    }
}
