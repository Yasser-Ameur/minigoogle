package com.minigoogle.indexer.integration;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.IndexBuilder;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for end-to-end IndexBuilder integration. */
class IndexBuilderIntegrationTest {
    @Test
    void testEndToEndIndexing() throws Exception {
        IndexBuilder builder = new IndexBuilder();

        ParsedDocument doc1 = new ParsedDocument(
            UUID.randomUUID(), 
            URI.create("https://example.com/1"),
            "Title 1", 
            "The quick brown fox jumps over the lazy dog.", 
            List.of(), 
            Instant.now()
        );
        
        ParsedDocument doc2 = new ParsedDocument(
            UUID.randomUUID(), 
            URI.create("https://example.com/2"),
            "Title 2", 
            "The fast brown fox runs.", 
            List.of(), 
            Instant.now()
        );

        builder.processDocument(doc1);
        builder.processDocument(doc2);

        File dictFile = File.createTempFile("dictionary", ".bin");
        File postFile = File.createTempFile("postings", ".bin");
        File docFile = File.createTempFile("documents", ".bin");

        builder.flush(dictFile.getAbsolutePath(), postFile.getAbsolutePath(), docFile.getAbsolutePath());

        assertTrue(Files.size(dictFile.toPath()) > 0);
        assertTrue(Files.size(postFile.toPath()) > 0);
        assertTrue(Files.size(docFile.toPath()) > 0);
    }
}
