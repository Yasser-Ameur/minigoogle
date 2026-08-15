package com.minigoogle.query.integration;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.parser.Parser;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.planner.QueryPlanner;
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

/** Tests for end-to-end query engine integration. */
class QueryIntegrationTest {
    @Test
    void testEndToEndQueryEngine() throws Exception {
        // 1. Build Index
        IndexBuilder builder = new IndexBuilder();

        builder.processDocument(new ParsedDocument(UUID.randomUUID(), URI.create("https://doc1"), "Doc 1", "java is a programming language", List.of(), Instant.now()));
        builder.processDocument(new ParsedDocument(UUID.randomUUID(), URI.create("https://doc2"), "Doc 2", "python is a language", List.of(), Instant.now()));
        builder.processDocument(new ParsedDocument(UUID.randomUUID(), URI.create("https://doc3"), "Doc 3", "java compiler is fast", List.of(), Instant.now()));

        File dir = Files.createTempDirectory("query_test").toFile();
        dir.deleteOnExit();
        Path dictPath = dir.toPath().resolve("dictionary.bin");
        Path postPath = dir.toPath().resolve("postings.bin");
        Path docPath = dir.toPath().resolve("documents.bin");
        
        builder.flush(dictPath.toString(), postPath.toString(), docPath.toString());

        // 2. Load Index
        Map<String, DictionaryEntry> dictionary = new DictionaryReader().read(dictPath);
        
        try (MemoryMappedIndex index = new MemoryMappedIndex(postPath)) {
            // 3. Setup Query Engine
            QueryPlanner planner = new QueryPlanner(index, dictionary, 3);
            Lexer lexer = new Lexer();
            
            // Query 1: java AND language
            Parser parser1 = new Parser(lexer.tokenize("java AND language"));
            QueryNode ast1 = parser1.parse();
            PostingList result1 = planner.execute(ast1);
            
            assertEquals(1, result1.getPostings().size()); // Only doc 1
            
            // Query 2: java OR python
            Parser parser2 = new Parser(lexer.tokenize("java OR python"));
            QueryNode ast2 = parser2.parse();
            PostingList result2 = planner.execute(ast2);
            
            assertEquals(3, result2.getPostings().size()); // All 3 docs have either java or python
            
            // Query 3: Phrase "java compiler"
            Parser parser3 = new Parser(lexer.tokenize("\"java compiler\""));
            QueryNode ast3 = parser3.parse();
            PostingList result3 = planner.execute(ast3);
            
            assertEquals(1, result3.getPostings().size()); // Only doc 3

            // Query 4: NOT java (root-level complement over the universe)
            Parser parser4 = new Parser(lexer.tokenize("NOT java"));
            QueryNode ast4 = parser4.parse();
            PostingList result4 = planner.execute(ast4);

            assertEquals(1, result4.getPostings().size()); // only doc 2
            assertEquals(2, result4.getPostings().get(0).getDocumentId());

            // Query 5: java AND NOT compiler (nested negation)
            Parser parser5 = new Parser(lexer.tokenize("java AND NOT compiler"));
            QueryNode ast5 = parser5.parse();
            PostingList result5 = planner.execute(ast5);

            assertEquals(1, result5.getPostings().size()); // Only doc 1
            assertEquals(1, result5.getPostings().get(0).getDocumentId());
        }
    }
}
