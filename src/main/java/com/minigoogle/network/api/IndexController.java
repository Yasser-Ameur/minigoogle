package com.minigoogle.network.api;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.network.dto.ErrorResponse;
import com.minigoogle.network.serialization.JsonSerializer;

import java.util.function.Consumer;

/**
 * Controller exposing the /api/v1/index/document endpoint.
 */
public class IndexController {

    private final RestServer server;
    private final Consumer<ParsedDocument> indexHandler;

    public IndexController(RestServer server, Consumer<ParsedDocument> indexHandler) {
        this.server = server;
        this.indexHandler = indexHandler;
        setupRoutes();
    }

    private void setupRoutes() {
        server.post("/api/v1/index/document", body -> {
            try {
                ParsedDocument doc = JsonSerializer.fromJson(body, ParsedDocument.class);
                if (doc == null || doc.url() == null) {
                    return JsonSerializer.toJson(new ErrorResponse("INVALID_DOCUMENT", "ParsedDocument or URL is null"));
                }
                indexHandler.accept(doc);
                return "{\"status\":\"SUCCESS\"}";
            } catch (Exception e) {
                return JsonSerializer.toJson(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
            }
        });
    }
}
