package com.minigoogle.ranking.normalization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ScoreNormalizer Min-Max normalization. */
class ScoreNormalizerTest {

    private static final double EPS = 1e-12;

    @Test
    void testSpreadMapsOntoUnitInterval() {
        ScoreNormalizer normalizer = new ScoreNormalizer();
        double[] scores = {2.0, 6.0, 4.0};

        normalizer.normalizeInPlace(scores, scores.length);

        assertArrayEquals(new double[]{0.0, 1.0, 0.5}, scores, EPS);
    }

    @Test
    void testIdenticalScoresBecomeMidpoint() {
        ScoreNormalizer normalizer = new ScoreNormalizer();
        double[] scores = {3.0, 3.0, 3.0};

        normalizer.normalizeInPlace(scores, scores.length);

        assertArrayEquals(new double[]{0.5, 0.5, 0.5}, scores, EPS);
    }

    @Test
    void testAllZeroScoresBecomeMidpoint() {
        // An all-zero signal is the common case for PageRank on a corpus with no
        // link graph. Seeding max with Double.MIN_VALUE - the smallest positive
        // double, not the most negative one - left the range at 4.9e-324 instead
        // of 0, so this fell through to division and produced 0.0 for every
        // document rather than the documented midpoint.
        ScoreNormalizer normalizer = new ScoreNormalizer();
        double[] scores = {0.0, 0.0, 0.0};

        normalizer.normalizeInPlace(scores, scores.length);

        assertArrayEquals(new double[]{0.5, 0.5, 0.5}, scores, EPS);
    }

    @Test
    void testOnlyTheLeadingCountIsNormalized() {
        // The pipeline sizes its arrays from the posting budget and fills only a
        // prefix, so entries past count must be left untouched.
        ScoreNormalizer normalizer = new ScoreNormalizer();
        double[] scores = {2.0, 6.0, 99.0, 99.0};

        normalizer.normalizeInPlace(scores, 2);

        assertArrayEquals(new double[]{0.0, 1.0, 99.0, 99.0}, scores, EPS);
    }

    @Test
    void testEmptyAndNullAreNoOps() {
        ScoreNormalizer normalizer = new ScoreNormalizer();
        double[] scores = {7.0};

        normalizer.normalizeInPlace(scores, 0);
        assertEquals(7.0, scores[0], EPS);

        assertDoesNotThrow(() -> normalizer.normalizeInPlace(null, 3));
    }
}
