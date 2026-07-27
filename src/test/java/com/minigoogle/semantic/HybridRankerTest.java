package com.minigoogle.semantic;

import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.query.result.SearchResult;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for HybridRanker functionality. */
class HybridRankerTest {

    private SearchResult makeResult(UUID docId, double score) {
        IndexedDocument doc = new IndexedDocument(
                docId, URI.create("http://example.com/" + docId), "Doc " + docId, 100, Instant.now());
        return new SearchResult(doc, score);
    }

    @Test
    void testDefaultAlphaBlendsEqually() {
        HybridRanker ranker = new HybridRanker();
        assertEquals(0.5, ranker.getAlpha(), 0.001);
    }

    @Test
    void testPureLexicalRanking() {
        HybridRanker ranker = new HybridRanker(1.0);
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        UUID doc3 = UUID.randomUUID();
        List<SearchResult> lexical = List.of(makeResult(doc1, 10.0), makeResult(doc2, 5.0));
        List<SearchResult> semantic = List.of(makeResult(doc2, 100.0), makeResult(doc3, 50.0));

        List<SearchResult> results = ranker.rank(lexical, semantic, 10);
        assertEquals(3, results.size());
        assertEquals(1.0, results.get(0).score(), 0.01);
        assertEquals(0.5, results.get(1).score(), 0.01);
        assertEquals(0.0, results.get(2).score(), 0.01);
    }

    @Test
    void testPureSemanticRanking() {
        HybridRanker ranker = new HybridRanker(0.0);
        UUID doc1 = UUID.randomUUID();
        List<SearchResult> lexical = List.of(makeResult(doc1, 10.0));
        List<SearchResult> semantic = List.of(makeResult(doc1, 20.0));

        List<SearchResult> results = ranker.rank(lexical, semantic, 10);
        assertEquals(1, results.size());
        assertEquals(1.0, results.get(0).score(), 0.01);
    }

    @Test
    void testBlendingCombinesScores() {
        HybridRanker ranker = new HybridRanker(0.5);
        UUID doc1 = UUID.randomUUID();
        List<SearchResult> lexical = List.of(makeResult(doc1, 10.0));
        List<SearchResult> semantic = List.of(makeResult(doc1, 20.0));

        List<SearchResult> results = ranker.rank(lexical, semantic, 10);
        assertEquals(1, results.size());
        double expected = 0.5 * (10.0 / 10.0) + 0.5 * (20.0 / 20.0);
        assertEquals(expected, results.get(0).score(), 0.01);
    }

    @Test
    void testTopKLimitsResults() {
        HybridRanker ranker = new HybridRanker(0.5);
        List<SearchResult> lexical = new ArrayList<>();
        List<SearchResult> semantic = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID docId = UUID.randomUUID();
            lexical.add(makeResult(docId, i * 10.0));
            semantic.add(makeResult(docId, i * 5.0));
        }

        List<SearchResult> results = ranker.rank(lexical, semantic, 3);
        assertEquals(3, results.size());
    }

    @Test
    void testInvalidAlphaThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new HybridRanker(1.1));
    }

    @Test
    void testEmptyResults() {
        HybridRanker ranker = new HybridRanker(0.5);
        List<SearchResult> results = ranker.rank(List.of(), List.of(), 10);
        assertTrue(results.isEmpty());
    }
}
