package com.minigoogle.network.client;

import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.retry.RetryPolicy;
import com.minigoogle.network.serialization.JsonSerializer;
import com.minigoogle.network.util.RequestIdGenerator;

/**
 * Typed client for communicating with the Search API.
 * Handles serialization, request IDs, and automatic retries.
 */
public class SearchClient {

    private final RestClient restClient;
    private final RetryPolicy retryPolicy;

    public SearchClient(RestClient restClient, RetryPolicy retryPolicy) {
        this.restClient = restClient;
        this.retryPolicy = retryPolicy;
    }

    public SearchClient(RestClient restClient) {
        this(restClient, new RetryPolicy());
    }

    /**
     * Sends a search request to the given base URL with automatic retries.
     */
    public SearchResponse search(String baseUrl, SearchRequest request) throws Exception {
        String requestId = RequestIdGenerator.generate();
        String payload = JsonSerializer.toJson(request);

        String responseBody = retryPolicy.execute(() ->
                restClient.post(baseUrl + "/api/v1/search", payload));

        return JsonSerializer.fromJson(responseBody, SearchResponse.class);
    }
}
