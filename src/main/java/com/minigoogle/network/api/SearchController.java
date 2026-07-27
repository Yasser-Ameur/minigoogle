package com.minigoogle.network.api;

import com.minigoogle.network.http.RestServer;
import com.minigoogle.network.dto.ErrorResponse;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.serialization.JsonSerializer;

import java.util.List;
import java.util.function.Function;

/**
 * Controller exposing the /api/v1/search endpoint.
 */
public class SearchController {

    private final RestServer server;
    private final Function<SearchRequest, SearchResponse> searchHandler;

    public SearchController(RestServer server, Function<SearchRequest, SearchResponse> searchHandler) {
        this.server = server;
        this.searchHandler = searchHandler;
        setupRoutes();
    }

    private void setupRoutes() {
        server.post("/api/v1/search", body -> {
            try {
                SearchRequest request = JsonSerializer.fromJson(body, SearchRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()) {
                    return JsonSerializer.toJson(new ErrorResponse("INVALID_QUERY", "Query cannot be empty"));
                }
                SearchResponse response = searchHandler.apply(request);
                return JsonSerializer.toJson(response);
            } catch (Exception e) {
                return JsonSerializer.toJson(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
            }
        });
    }
}
