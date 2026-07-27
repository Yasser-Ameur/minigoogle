package com.minigoogle.network.client;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.retry.RetryPolicy;
import com.minigoogle.network.serialization.JsonSerializer;

/**
 * Typed client for communicating with the Index API.
 * Sends documents for indexing with automatic retries.
 */
public class IndexClient {

    private final RestClient restClient;
    private final RetryPolicy retryPolicy;

    public IndexClient(RestClient restClient, RetryPolicy retryPolicy) {
        this.restClient = restClient;
        this.retryPolicy = retryPolicy;
    }

    public IndexClient(RestClient restClient) {
        this(restClient, new RetryPolicy());
    }

    /**
     * Sends a parsed document to an index node for indexing.
     */
    public void indexDocument(String indexNodeUrl, ParsedDocument document) throws Exception {
        String payload = JsonSerializer.toJson(document);
        retryPolicy.execute(() ->
                restClient.post(indexNodeUrl + "/api/v1/index/document", payload));
    }
}
