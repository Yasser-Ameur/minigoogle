package com.minigoogle.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads a BEIR {@code queries.jsonl} (optionally gzip-compressed) into ordered
 * {@link BeirQuery}s. Query order is preserved exactly as listed.
 */
public final class BeirQueriesReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BeirQueriesReader() {
    }

    public static List<BeirQuery> read(Path queriesFile) throws IOException {
        List<BeirQuery> queries = new ArrayList<>();
        try (BufferedReader reader = open(queriesFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String id = node.path("_id").asText(null);
                    String text = node.path("text").asText(null);
                    if (id != null && !id.isEmpty() && text != null && !text.isEmpty()) {
                        queries.add(new BeirQuery(id, text));
                    }
                } catch (Exception ignored) {
                    // Skip malformed query lines.
                }
            }
        }
        return List.copyOf(queries);
    }

    private static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);
    }
}
