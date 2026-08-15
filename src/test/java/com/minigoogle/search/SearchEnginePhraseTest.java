package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.ranking.model.RankedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for end-to-end phrase handling through the shared retrieval
 * engine: a quoted phrase must match exact adjacent words and never be
 * flattened into an OR-of-words by query expansion.
 */
class SearchEnginePhraseTest {

    @TempDir
    Path tempDir;

    private SearchEngine engine() throws Exception {
        List<ParsedDocument> docs = List.of(
                new ParsedDocument(UUID.randomUUID(), URI.create("https://doc1"), "Doc 1",
                        "java is a programming language", List.of(), Instant.now()),
                new ParsedDocument(UUID.randomUUID(), URI.create("https://doc2"), "Doc 2",
                        "python is a language", List.of(), Instant.now()),
                new ParsedDocument(UUID.randomUUID(), URI.create("https://doc3"), "Doc 3",
                        "java compiler is fast", List.of(), Instant.now()),
                new ParsedDocument(UUID.randomUUID(), URI.create("https://doc4"), "Doc 4",
                        "the compiler here is java", List.of(), Instant.now()));

        Configuration config = new Configuration(Map.of(
                "semantic.enabled", "false",
                "semantic.hybrid.enabled", "false",
                "semantic.expansion.enabled", "true"));
        return SearchEngineBuilder.build(docs, config, tempDir).engine();
    }

    @Test
    void phraseQueryMatchesOnlyExactAdjacentSequence() throws Exception {
        RetrievalResult result = engine().retrieveCandidates("\"java compiler\"", 10);
        assertEquals(1, result.ranked().size());
        assertEquals("https://doc3", result.ranked().get(0).url());
    }

    @Test
    void unquotedMultiWordQueryUsesImplicitAnd() throws Exception {
        RetrievalResult result = engine().retrieveCandidates("java compiler", 10);
        List<String> urls = result.ranked().stream().map(RankedDocument::url).toList();
        assertTrue(urls.contains("https://doc3"));
        assertTrue(urls.contains("https://doc4"));
        assertFalse(urls.contains("https://doc1"));
        assertFalse(urls.contains("https://doc2"));
    }
}
