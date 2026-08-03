package com.minigoogle.demo;

import com.minigoogle.network.http.RestServer;
import com.minigoogle.semantic.autocomplete.TrieAutocomplete;
import com.minigoogle.semantic.spell.SpellCorrector;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for GET handlers receiving the URI query string.
 *
 * <p>Previously the RestServer passed the (empty) request body to GET handlers,
 * so {@code /api/v1/suggest?q=text} produced the generic prefix "" result set
 * instead of matching the typed prefix.</p>
 */
class RestServerSuggestHttpTest {

    @Test
    void suggestPrefixArrivesFromQueryString() throws Exception {
        TrieAutocomplete autocomplete = new TrieAutocomplete();
        for (String word : List.of("java", "javascript", "testing", "text", "texting")) {
            autocomplete.addWord(word);
        }

        RestServer server = new RestServer(0);
        server.getWithContentType("/api/v1/suggest", "application/json", req -> {
            String prefix = req.replaceAll("^.*[?&]q=", "").replaceAll("&.*$", "");
            prefix = URLDecoder.decode(prefix, StandardCharsets.UTF_8).trim().toLowerCase();
            List<String> suggestions = autocomplete.autocomplete(prefix, 8);
            return suggestions.toString();
        });
        server.start();
        int port = server.getPort();
        try {
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(
                    "[text, texting]",
                    get(client, port, "/api/v1/suggest?q=tex"),
                    "typed prefix 'tex' must drive the suggestions");
            assertEquals(
                    "[testing]",
                    get(client, port, "/api/v1/suggest?q=testin"),
                    "typed prefix 'testin' must drive the suggestions");
            assertEquals(
                    "[]",
                    get(client, port, "/api/v1/suggest?q=zzzz"),
                    "non-existent prefix must return no suggestions");
        } finally {
            server.stop();
        }
    }

    private String get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
