package com.minigoogle.storage.documents;

import com.minigoogle.crawler.model.ParsedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the crawled-document durable store. */
class CrawledDocumentStoreTest {

    @TempDir
    Path tempDir;

    private Path storeFile() {
        return tempDir.resolve("nested/crawled-documents.jsonl");
    }

    private ParsedDocument doc(String url, String title, String text, Instant crawlTime, URI... links) {
        return new ParsedDocument(UUID.randomUUID(), URI.create(url), title, text, List.of(links), crawlTime);
    }

    @Test
    void roundTripsUnicodeTextLinksAndCrawlTime() throws IOException {
        ParsedDocument original = doc("https://example.com/a", "café – 日本",
                "unicode body éèà 中文", Instant.parse("2026-01-01T00:00:00Z"),
                URI.create("https://example.com/b"), URI.create("https://example.com/c"));

        try (CrawledDocumentStore store = CrawledDocumentStore.open(storeFile())) {
            store.append(original);
            List<ParsedDocument> all = store.readAll();
            assertEquals(1, all.size());
            assertEquals(original, all.get(0));
        }
    }

    @Test
    void appendThenReopenSeesTheDocument() throws IOException {
        Path file = storeFile();
        ParsedDocument original = doc("https://example.com/x", "title", "text", Instant.now());

        try (CrawledDocumentStore store = CrawledDocumentStore.open(file)) {
            store.append(original);
        }

        try (CrawledDocumentStore reopened = CrawledDocumentStore.open(file)) {
            List<ParsedDocument> all = reopened.readAll();
            assertEquals(1, all.size());
            assertEquals(original, all.get(0));
        }
    }

    @Test
    void truncatedFinalLineIsSkipped() throws IOException {
        Path file = storeFile();
        ParsedDocument first = doc("https://example.com/first", "first", "text", Instant.now());

        try (CrawledDocumentStore store = CrawledDocumentStore.open(file)) {
            store.append(first);
        }
        // Simulate a crash mid-write: append a truncated JSON fragment.
        Files.writeString(file, "{\"id\":\"broken", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        try (CrawledDocumentStore reopened = CrawledDocumentStore.open(file)) {
            List<ParsedDocument> all = reopened.readAll();
            assertEquals(1, all.size());
            assertEquals(first, all.get(0));
        }
    }

    @Test
    void secondAppendForSameUrlReplacesTheFirst() throws IOException {
        Instant firstTime = Instant.parse("2026-01-01T00:00:00Z");
        Instant secondTime = Instant.parse("2026-02-02T00:00:00Z");
        ParsedDocument v1 = doc("https://example.com/same", "old title", "old text", firstTime);
        ParsedDocument v2 = doc("https://example.com/same", "new title", "new text", secondTime);

        try (CrawledDocumentStore store = CrawledDocumentStore.open(storeFile())) {
            store.append(v1);
            store.append(v2);
            List<ParsedDocument> all = store.readAll();
            assertEquals(1, all.size());
            assertEquals(v2, all.get(0));
            assertEquals(1, store.count());
        }
    }

    @Test
    void emptyFileReturnsEmptyList() throws IOException {
        try (CrawledDocumentStore store = CrawledDocumentStore.open(storeFile())) {
            assertTrue(store.readAll().isEmpty());
            assertEquals(0, store.count());
        }
    }

    @Test
    void textContainingNewlineRoundTrips() throws IOException {
        ParsedDocument original = doc("https://example.com/multiline", "title",
                "line one\nline two\nline three", Instant.now());

        try (CrawledDocumentStore store = CrawledDocumentStore.open(storeFile())) {
            store.append(original);
            List<ParsedDocument> all = store.readAll();
            assertEquals(1, all.size());
            assertEquals(original, all.get(0));
        }
    }
}
