package com.minigoogle.ranking.bm25;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for BM25Calculator ranking functionality. */
class BM25CalculatorTest {

    @Test
    void testIdfRareTermHighScore() {
        BM25Parameters params = BM25Parameters.withDefaults(100_000, 420);
        BM25Calculator calc = new BM25Calculator(params);

        // Rare term (df=100 out of 100,000) should have high IDF
        double idfRare = calc.idf(100);
        // Common term (df=50,000 out of 100,000) should have low IDF
        double idfCommon = calc.idf(50_000);

        assertTrue(idfRare > idfCommon, "Rare terms should have higher IDF than common terms");
        assertTrue(idfRare > 0, "IDF should be positive");
        assertTrue(idfCommon > 0, "Smoothed IDF should still be positive");
    }

    @Test
    void testScoreTermHighTfBeatLowTf() {
        BM25Parameters params = BM25Parameters.withDefaults(10_000, 400);
        BM25Calculator calc = new BM25Calculator(params);

        double scoreHighTf = calc.scoreTermInDocument(10, 400, 500);
        double scoreLowTf = calc.scoreTermInDocument(1, 400, 500);

        assertTrue(scoreHighTf > scoreLowTf,
                "Higher term frequency should produce higher score");
    }

    @Test
    void testScoreTermShorterDocBeatLongerDoc() {
        BM25Parameters params = BM25Parameters.withDefaults(10_000, 400);
        BM25Calculator calc = new BM25Calculator(params);

        // Same TF, but shorter document should score higher
        double scoreShort = calc.scoreTermInDocument(5, 200, 500);
        double scoreLong = calc.scoreTermInDocument(5, 5000, 500);

        assertTrue(scoreShort > scoreLong,
                "Shorter documents with same TF should score higher (BM25 length normalization)");
    }

    @Test
    void testMultiTermScore() {
        BM25Parameters params = BM25Parameters.withDefaults(10_000, 400);
        BM25Calculator calc = new BM25Calculator(params);

        List<String> terms = List.of("distributed", "systems");
        Map<String, Integer> tf = Map.of("distributed", 3, "systems", 2);
        Map<String, Integer> df = Map.of("distributed", 500, "systems", 1000);

        double score = calc.scoreDocument(terms, tf, 400, df);
        assertTrue(score > 0, "Multi-term BM25 score should be positive");

        // Score should be sum of individual term scores
        double individual1 = calc.scoreTermInDocument(3, 400, 500);
        double individual2 = calc.scoreTermInDocument(2, 400, 1000);
        assertEquals(individual1 + individual2, score, 0.0001);
    }
}
