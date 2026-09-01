package com.minigoogle.storage.documents;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.minigoogle.crawler.model.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable append-only store for documents submitted through the crawl API.
 *
 * <p>One JSON object per line ({@code .jsonl}). Every {@link #append} fsyncs
 * before returning, so an acknowledged document survives a crash. {@link
 * #readAll()} tolerates a torn final line — the expected result of a crash
 * mid-append — by skipping it with a WARN log rather than failing the whole
 * read; a later append for a URL already seen replaces the earlier record,
 * keeping the order of first appearance.</p>
 */
public final class CrawledDocumentStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CrawledDocumentStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** JSON-friendly mirror of {@link ParsedDocument}: URI and Instant as strings. */
    private record Record(String id, String url, String title, String text,
                           List<String> outgoingLinks, String crawlTime) {

        static Record from(ParsedDocument doc) {
            List<String> links = new ArrayList<>();
            for (URI link : doc.outgoingLinks()) {
                links.add(link.toString());
            }
            return new Record(doc.id().toString(), doc.url().toString(), doc.title(), doc.text(),
                    links, doc.crawlTime().toString());
        }

        ParsedDocument toParsedDocument() {
            List<URI> links = new ArrayList<>();
            for (String link : outgoingLinks) {
                links.add(URI.create(link));
            }
            return new ParsedDocument(UUID.fromString(id), URI.create(url), title, text, links,
                    Instant.parse(crawlTime));
        }
    }

    private final Path file;
    private long count;

    private CrawledDocumentStore(Path file, long count) {
        this.file = file;
        this.count = count;
    }

    /**
     * Opens the store, creating the parent directory and the file itself if
     * either is missing.
     */
    public static CrawledDocumentStore open(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        CrawledDocumentStore store = new CrawledDocumentStore(file, 0);
        store.count = store.readAll().size();
        return store;
    }

    /**
     * Appends one document as a single JSON line, fsynced before returning.
     */
    public synchronized void append(ParsedDocument doc) throws IOException {
        String line = MAPPER.writeValueAsString(Record.from(doc));
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        count = readAll().size();
    }

    /**
     * Reads every document in append order. A truncated or corrupt final line
     * is skipped with a WARN log rather than raised as an exception. When the
     * same URL was appended more than once, the later record wins, but the
     * position in the returned list is that of its first appearance.
     */
    public synchronized List<ParsedDocument> readAll() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, ParsedDocument> byUrl = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                Record record = MAPPER.readValue(line, Record.class);
                ParsedDocument doc = record.toParsedDocument();
                byUrl.put(doc.url().toString(), doc);
            } catch (Exception e) {
                boolean isLastLine = i == lines.size() - 1;
                logger.warn("Skipping {} line {} in {}: {}",
                        isLastLine ? "truncated/corrupt" : "corrupt", i + 1, file, e.getMessage());
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    /** Number of documents currently readable (after last-write-wins dedup by URL). */
    public long count() {
        return count;
    }

    @Override
    public void close() throws IOException {
        // No open resources to release: append() and readAll() each open and
        // close their own channel.
    }
}
