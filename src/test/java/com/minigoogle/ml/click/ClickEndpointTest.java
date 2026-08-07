package com.minigoogle.ml.click;

import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.network.dto.ClickRequest;
import com.minigoogle.network.http.RestClient;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.network.serialization.JsonSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the /api/v1/click endpoint wires clicks into the feedback loop.
 */
class ClickEndpointTest {

    private RestServer server;
    private ClickTracker tracker;
    private ClickFeedbackTrainer trainer;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, String> urls = new HashMap<>();
        urls.put(1, "http://example.com/alpha");
        urls.put(2, "http://example.com/alpha-two");
        Map<Integer, String> titles = new HashMap<>();
        titles.put(1, "Alpha");
        titles.put(2, "Alpha Two");
        Map<Integer, String> bodies = new HashMap<>();
        bodies.put(1, "alpha alpha");
        bodies.put(2, "alpha alpha alpha");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 100);
        lengths.put(2, 100);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.5);
        pageRanks.put(2, 0.5);

        FeatureExtractor extractor = new FeatureExtractor(urls, titles, bodies, lengths, pageRanks, null, null);
        LinearRankingModel model = new LinearRankingModel();
        tracker = new ClickTracker();
        tracker.recordImpression("alpha", List.of(1, 2));
        trainer = new ClickFeedbackTrainer(extractor, model, tracker, 1, 10, 0.1);

        server = new RestServer(0);
        server.post("/api/v1/click", body -> {
            ClickRequest request = JsonSerializer.fromJson(body, ClickRequest.class);
            int trainedPairs = trainer.onClick(new ClickEvent(
                    request.query(), request.documentId(), request.url(),
                    request.position() > 0 ? request.position() : 1,
                    null, request.sessionId()));
            return "{\"success\":true,\"trainedPairs\":" + trainedPairs + "}";
        });
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testClickEndpointRecordsAndTrains() {
        RestClient client = new RestClient();
        ClickRequest request = new ClickRequest("alpha", 2, "http://example.com/alpha-two", 2, "session-1");
        String responseBody = client.post("http://localhost:" + port + "/api/v1/click",
                JsonSerializer.toJson(request));

        assertTrue(responseBody.contains("\"success\":true"), "Response should be a success: " + responseBody);
        assertEquals(1, tracker.clickCount());
        assertTrue(responseBody.contains("\"trainedPairs\":1"),
                "Clicking position 2 over position 1 should train on one pair: " + responseBody);
    }

    @Test
    void testClickEndpointWithoutPositionDefaultsToOne() {
        RestClient client = new RestClient();
        ClickRequest request = new ClickRequest("alpha", 1, "http://example.com/alpha", 0, null);
        String responseBody = client.post("http://localhost:" + port + "/api/v1/click",
                JsonSerializer.toJson(request));

        assertTrue(responseBody.contains("\"success\":true"));
        // Position 1 is the top result: no preference pairs can be derived.
        assertTrue(responseBody.contains("\"trainedPairs\":0"));
        assertEquals(1, tracker.clickCount());
    }
}
