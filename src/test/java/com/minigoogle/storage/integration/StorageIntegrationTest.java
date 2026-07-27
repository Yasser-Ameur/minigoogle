package com.minigoogle.storage.integration;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for end-to-end storage integration. */
class StorageIntegrationTest {
    @Test
    void testStorageEngineRoundTrip() throws Exception {
        IndexBuilder builder = new IndexBuilder();

        ParsedDocument doc1 = new ParsedDocument(
            UUID.randomUUID(), 
            URI.create("https://example.com/1"),
            "Title 1", 
            "apple banana apple", 
            List.of(), 
            Instant.now()
        );
        
        ParsedDocument doc2 = new ParsedDocument(
            UUID.randomUUID(), 
            URI.create("https://example.com/2"),
            "Title 2", 
            "banana orange", 
            List.of(), 
            Instant.now()
        );

        builder.processDocument(doc1);
        builder.processDocument(doc2);

        File dir = Files.createTempDirectory("storage_test").toFile();
        dir.deleteOnExit();
        
        Path dictPath = dir.toPath().resolve("dictionary.bin");
        Path postPath = dir.toPath().resolve("postings.bin");
        Path docPath = dir.toPath().resolve("documents.bin");
        
        builder.flush(dictPath.toString(), postPath.toString(), docPath.toString());
        
        // 1. Read Dictionary
        Map<String, DictionaryEntry> dict = new DictionaryReader().read(dictPath);
        assertTrue(dict.containsKey("apple")); // Stemmed "apple"
        assertTrue(dict.containsKey("banana"));
        assertTrue(dict.containsKey("orange"));
        
        // 2. Read Postings via MemoryMappedIndex
        try (MemoryMappedIndex index = new MemoryMappedIndex(postPath)) {
            DictionaryEntry bananaEntry = dict.get("banana");
            PostingList bananaPostings = index.readPostingList(bananaEntry.postingOffset());
            
            assertEquals(2, bananaPostings.getPostings().size()); // Occurs in doc1 and doc2
        }
    }
}
