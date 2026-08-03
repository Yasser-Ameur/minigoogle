package com.minigoogle.semantic.reranking;

import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the content-based semantic CrossEncoderRanker. */
class CrossEncoderRankerTest {

    private final EmbeddingGenerator generator = new EmbeddingGenerator(64);

    private RankedDocument doc(int id, String title, String body, double bm25) {
        return new RankedDocument(id, "http://example.com/" + id, title, bm25, 0, bm25, body);
    }

    private VectorIndex buildIndex(RankedDocument... docs) {
        VectorIndex index = new VectorIndex(64);
        for (RankedDocument d : docs) {
            index.add(d.documentId(), generator.embed(d.title() + " " + d.snippet()), d.title());
        }
        return index;
    }

    @Test
    void semanticRerankRanksVocabularyMatchAboveUnrelated() {
        RankedDocument aboutSearch = doc(1, "Distributed search engine",
                "A distributed search engine indexes documents across many machines", 1.0);
        RankedDocument aboutCooking = doc(2, "Cooking recipes",
                "Recipes for pasta, bread and desserts for home cooks", 5.0);

        VectorIndex index = buildIndex(aboutSearch, aboutCooking);
        CrossEncoderRanker ranker = new CrossEncoderRanker(index, generator, 1.0);

        List<RankedDocument> reranked = ranker.rerank("distributed search engine", List.of(aboutCooking, aboutSearch));

        assertEquals(2, reranked.size());
        assertEquals(1, reranked.get(0).documentId());
        assertTrue(reranked.get(0).finalScore() > reranked.get(1).finalScore());
    }

    @Test
    void pureLexicalWeightPreservesBm25Ordering() {
        RankedDocument a = doc(1, "Alpha", "alpha beta gamma delta", 10.0);
        RankedDocument b = doc(2, "Beta", "epsilon zeta eta theta", 20.0);

        VectorIndex index = buildIndex(a, b);
        CrossEncoderRanker ranker = new CrossEncoderRanker(index, generator, 0.0);

        List<RankedDocument> reranked = ranker.rerank("alpha", List.of(a, b));
        assertEquals(2, reranked.get(0).documentId());
        assertEquals(1.0, reranked.get(0).finalScore(), 0.001);
    }

    @Test
    void missingVectorFallsBackToZeroSemanticScore() {
        RankedDocument a = doc(1, "Alpha", "alpha body text", 3.0);
        VectorIndex index = new VectorIndex(64);
        index.add(1, generator.embed("Alpha alpha body text"), "Alpha");

        CrossEncoderRanker ranker = new CrossEncoderRanker(index, generator, 0.5);
        RankedDocument unknown = new RankedDocument(99, "http://x", "Unknown", 7.0, 0, 7.0, "no vector");

        List<RankedDocument> reranked = ranker.rerank("alpha", List.of(a, unknown));
        assertEquals(2, reranked.size());
        assertEquals(1, reranked.get(0).documentId());
    }

    @Test
    void fallbackTermOverlapStillWorksWithoutIndex() {
        RankedDocument a = doc(1, "Search engines", "How search engines rank results", 1.0);
        RankedDocument b = doc(2, "Gardening", "How to grow tomatoes indoors", 5.0);

        CrossEncoderRanker ranker = new CrossEncoderRanker();
        List<RankedDocument> reranked = ranker.rerank("search engines rank", List.of(b, a));

        assertEquals(1, reranked.get(0).documentId());
    }

    @Test
    void invalidSemanticWeightThrows() {
        assertThrows(IllegalArgumentException.class, () -> new CrossEncoderRanker(new VectorIndex(4), 1.5));
        assertThrows(IllegalArgumentException.class, () -> new CrossEncoderRanker(new VectorIndex(4), -0.1));
    }

    private static void assertThrows(Class<? extends Throwable> type, Runnable r) {
        try {
            r.run();
            throw new AssertionError("Expected " + type.getSimpleName() + " but nothing was thrown");
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                throw new AssertionError("Expected " + type.getSimpleName() + " but got " + t, t);
            }
        }
    }
}
