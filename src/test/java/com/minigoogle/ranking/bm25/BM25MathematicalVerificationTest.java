package com.minigoogle.ranking.bm25;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies BM25 against values computed by hand, independently of the
 * implementation.
 *
 * <p>Every expected value below is derived arithmetically in the comment beside
 * it rather than by calling the implementation and recording what it returned —
 * a test written the latter way would pass against any formula, including a
 * wrong one. The convention under test is Okapi BM25 with the smoothed
 * ("plus-one") IDF that Lucene also uses:</p>
 *
 * <pre>
 *   IDF(t)   = ln( (N - df + 0.5) / (df + 0.5) + 1 )
 *   score(t) = IDF(t) * ( tf * (k1 + 1) ) / ( tf + k1 * (1 - b + b * |d| / avgdl) )
 * </pre>
 *
 * <p>Note the {@code + 1} inside the logarithm: it is what keeps IDF
 * non-negative for terms appearing in more than half the corpus. The classic
 * Robertson-Sparck Jones IDF omits it and goes negative there, so scores from
 * the two conventions are not comparable and neither is "wrong" — the point of
 * this test is that the implementation matches the convention it documents.</p>
 */
class BM25MathematicalVerificationTest {

    private static final double EPS = 1e-9;

    @Test
    void idfMatchesTheSmoothedFormula() {
        // N = 1000, df = 10
        //   (1000 - 10 + 0.5) / (10 + 0.5) + 1
        // = 990.5 / 10.5 + 1
        // = 94.333333... + 1 = 95.333333...
        //   ln(95.3333333...) = 4.5573795...
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        double expected = Math.log((1000 - 10 + 0.5) / (10 + 0.5) + 1.0);
        assertEquals(expected, calc.idf(10), EPS);
        assertEquals(4.5573795, calc.idf(10), 1e-6);
    }

    @Test
    void idfStaysNonNegativeForVeryCommonTerms() {
        // A term in 900 of 1000 documents:
        //   (1000 - 900 + 0.5) / (900 + 0.5) + 1 = 0.1115... + 1 = 1.1115...
        //   ln(1.1115...) = 0.1058046... > 0
        // Without the +1 this would be ln(0.1115) = -2.19, i.e. negative, and a
        // document could be penalised for containing a query term.
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));
        double idf = calc.idf(900);
        assertTrue(idf > 0, "smoothed IDF must stay non-negative, was " + idf);
        assertEquals(Math.log((1000 - 900 + 0.5) / (900 + 0.5) + 1.0), idf, EPS);
        assertEquals(0.1058046, idf, 1e-6);
    }

    @Test
    void singleTermScoreMatchesHandComputation() {
        // N = 1000, df = 10, tf = 3, |d| = 200, avgdl = 100, k1 = 1.2, b = 0.75
        //
        //   IDF        = ln(990.5 / 10.5 + 1)              = 4.5573795
        //   lengthNorm = 1 - 0.75 + 0.75 * (200 / 100)     = 0.25 + 1.5 = 1.75
        //   numerator  = 3 * (1.2 + 1)                     = 6.6
        //   denominator= 3 + 1.2 * 1.75                    = 3 + 2.1 = 5.1
        //   score      = 4.5573795 * (6.6 / 5.1)           = 4.5573795 * 1.2941176
        //              = 5.8977853...
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        double idf = Math.log((1000 - 10 + 0.5) / (10 + 0.5) + 1.0);
        double expected = idf * (3 * (1.2 + 1.0)) / (3 + 1.2 * (1 - 0.75 + 0.75 * (200.0 / 100.0)));

        assertEquals(expected, calc.scoreTermInDocument(3, 200, 10), EPS);
        assertEquals(5.8977853, calc.scoreTermInDocument(3, 200, 10), 1e-6);
    }

    @Test
    void termFrequencySaturates() {
        // The (k1 + 1) * tf / (tf + k1 * norm) shape must show diminishing
        // returns: doubling tf must add less than the previous doubling did.
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        double tf1 = calc.scoreTermInDocument(1, 100, 10);
        double tf2 = calc.scoreTermInDocument(2, 100, 10);
        double tf4 = calc.scoreTermInDocument(4, 100, 10);
        double tf8 = calc.scoreTermInDocument(8, 100, 10);

        assertTrue(tf2 > tf1 && tf4 > tf2 && tf8 > tf4, "score must increase with tf");
        assertTrue(tf2 - tf1 > tf4 - tf2, "gain from tf 1->2 must exceed 2->4");
        assertTrue(tf4 - tf2 > tf8 - tf4, "gain from tf 2->4 must exceed 4->8");

        // Asymptote: as tf -> infinity the ratio approaches (k1 + 1) = 2.2 times IDF.
        double huge = calc.scoreTermInDocument(1_000_000, 100, 10);
        double idf = calc.idf(10);
        assertTrue(huge < idf * 2.2, "score must stay below IDF * (k1 + 1)");
        assertTrue(huge > idf * 2.19, "score must approach IDF * (k1 + 1)");
    }

    @Test
    void longerDocumentsScoreLowerAtEqualTermFrequency() {
        // Length normalization: same tf, longer document, lower score.
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        double shortDoc = calc.scoreTermInDocument(3, 50, 10);
        double averageDoc = calc.scoreTermInDocument(3, 100, 10);
        double longDoc = calc.scoreTermInDocument(3, 400, 10);

        assertTrue(shortDoc > averageDoc, "a shorter document must score higher");
        assertTrue(averageDoc > longDoc, "a longer document must score lower");
    }

    @Test
    void bZeroDisablesLengthNormalization() {
        // With b = 0 the length term collapses to 1, so document length is
        // irrelevant. This pins the meaning of b before any tuning of it.
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.0, 1000, 100.0));

        assertEquals(calc.scoreTermInDocument(3, 10, 10),
                calc.scoreTermInDocument(3, 10_000, 10), EPS,
                "b = 0 must make the score independent of document length");
    }

    @Test
    void multiTermScoreIsTheSumOfTermScores() {
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        List<String> terms = List.of("alpha", "beta");
        Map<String, Integer> tf = Map.of("alpha", 3, "beta", 5);
        Map<String, Integer> df = Map.of("alpha", 10, "beta", 50);

        double expected = calc.scoreTermInDocument(3, 200, 10)
                + calc.scoreTermInDocument(5, 200, 50);

        assertEquals(expected, calc.scoreDocument(terms, tf, 200, df), EPS);
    }

    @Test
    void absentTermsContributeNothing() {
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        Map<String, Integer> tf = Map.of("alpha", 3);
        Map<String, Integer> df = Map.of("alpha", 10, "missing", 5);

        // "missing" has tf = 0 in this document, so it adds nothing.
        assertEquals(calc.scoreDocument(List.of("alpha"), tf, 200, df),
                calc.scoreDocument(List.of("alpha", "missing"), tf, 200, df), EPS);
    }

    @Test
    void aRepeatedQueryTermIsCountedOncePerOccurrenceInTheQuery() {
        // scoreDocument iterates the query term list, so a term listed twice
        // contributes twice. This documents the current convention rather than
        // asserting it is the only defensible one: Lucene deduplicates by
        // default, and the difference matters for queries like "covid covid".
        BM25Calculator calc = new BM25Calculator(new BM25Parameters(1.2, 0.75, 1000, 100.0));

        Map<String, Integer> tf = Map.of("alpha", 3);
        Map<String, Integer> df = Map.of("alpha", 10);

        double once = calc.scoreDocument(List.of("alpha"), tf, 200, df);
        double twice = calc.scoreDocument(List.of("alpha", "alpha"), tf, 200, df);

        assertEquals(2 * once, twice, EPS,
                "a duplicated query term currently doubles its contribution");
    }
}
