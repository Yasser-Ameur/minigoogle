package com.minigoogle.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.crawler.model.ParsedDocument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Streaming reader for a BEIR {@code corpus.jsonl} (optionally gzip-compressed).
 *
 * <p>Reads one JSON object per line, assigns each document a deterministic
 * internal id (1-based ordinal in file order — the same convention the indexer
 * uses), and maps every BEIR {@code _id} to that internal id so the qrels can
 * be resolved later. Malformed lines are counted and skipped, duplicate
 * {@code _id}s keep their first occurrence, and {@code maxDocs} caps the corpus
 * for experiments on small machines.</p>
 *
 * <p>BEIR corpora have no link graph and no real URLs, so each document gets an
 * empty link list and a synthetic URL derived from its {@code _id}. The URL is
 * an identifier only — it does not exist on the web — and the empty link list
 * means PageRank is never fabricated from fake links.</p>
 */
public final class BeirCorpusReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROGRESS_EVERY_LINES = 25_000;

    /** Progress snapshot: raw lines read, documents kept, malformed lines skipped. */
    public record Progress(long lines, long docsRead, long malformed) {
        @Override
        public String toString() {
            return String.format("lines=%d docs=%d malformed=%d", lines, docsRead, malformed);
        }
    }

    public record Result(List<ParsedDocument> docs,
                         Map<String, Integer> beirIdToDocId,
                         long lines,
                         long malformed,
                         long duplicates,
                         boolean capped) {
    }

    private BeirCorpusReader() {
    }

    public static Result read(Path corpusFile, String dataset, int maxDocs,
                              Consumer<Progress> onProgress) throws IOException {
        List<ParsedDocument> docs = new ArrayList<>();
        Map<String, Integer> beirIdToDocId = new HashMap<>();
        long lines = 0, malformed = 0, duplicates = 0;
        boolean capped = false;

        try (BufferedReader reader = open(corpusFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String id = text(node, "_id");
                    if (id == null || id.isEmpty()) {
                        malformed++;
                        continue;
                    }
                    if (beirIdToDocId.containsKey(id)) {
                        duplicates++;
                        continue;
                    }
                    beirIdToDocId.put(id, docs.size() + 1);
                    docs.add(new ParsedDocument(
                            deterministicUuid(dataset, id),
                            syntheticUrl(dataset, id),
                            text(node, "title"),
                            text(node, "text"),
                            List.of(),
                            Instant.EPOCH));
                    if (maxDocs > 0 && docs.size() >= maxDocs) {
                        capped = true;
                        break;
                    }
                    if (lines % PROGRESS_EVERY_LINES == 0 && onProgress != null) {
                        onProgress.accept(new Progress(lines, docs.size(), malformed));
                    }
                } catch (Exception e) {
                    malformed++;
                }
            }
        }
        if (onProgress != null) {
            onProgress.accept(new Progress(lines, docs.size(), malformed));
        }
        return new Result(List.copyOf(docs), Map.copyOf(beirIdToDocId), lines, malformed, duplicates, capped);
    }

    /**
     * Deterministic v3 UUID per (dataset, _id) pair — the same document always
     * gets the same UUID, independent of run order or machine.
     */
    public static UUID deterministicUuid(String dataset, String beirId) {
        return UUID.nameUUIDFromBytes(("beir:" + dataset + ":" + beirId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Synthetic identifier URL: {@code https://beir.local/<dataset>/<_id>}.
     * Distinct {@code _id}s always map to distinct URLs, so URL dedup in the
     * coordinator behaves exactly as it does for real documents.
     */
    public static URI syntheticUrl(String dataset, String beirId) {
        return URI.create("https://beir.local/" + dataset + "/" + beirId);
    }

    public static Path corpusFile(Path datasetDir) {
        Path plain = datasetDir.resolve("corpus.jsonl");
        return Files.exists(plain) ? plain : datasetDir.resolve("corpus.jsonl.gz");
    }

    private static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }
}
