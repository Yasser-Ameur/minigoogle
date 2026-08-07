package com.minigoogle.ml.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the raw-to-normalized feature normalizer. */
class FeatureNormalizerTest {

    private static final RawFeatures RAW = new RawFeatures(
            0.5, 8.0, 0.4, 0.3, 0.6, 0.9, 500, 0.0);

    @Test
    void normalizesCorpusRelativeFeaturesAgainstContext() {
        QueryDocumentFeatures f = FeatureNormalizer.normalize(
                RAW, new NormalizationContext(16.0, 1000), 0);

        assertEquals(0.5, f.get(FeatureName.BM25), 0.001);
        assertEquals(0.5, f.get(FeatureName.PAGE_RANK), 0.001); // 8 / 16
        assertEquals(0.4, f.get(FeatureName.TITLE_MATCH), 0.001);
        assertEquals(0.3, f.get(FeatureName.URL_MATCH), 0.001);
        assertEquals(0.6, f.get(FeatureName.TERM_OVERLAP), 0.001);
        assertEquals(0.9, f.get(FeatureName.SEMANTIC_SIMILARITY), 0.001);
        assertEquals(Math.log1p(500) / Math.log1p(1000), f.get(FeatureName.DOC_LENGTH), 0.001);
    }

    @Test
    void positionFeatureReflectsRankPosition() {
        assertEquals(1.0, FeatureNormalizer.normalize(RAW, NormalizationContext.EMPTY, 0)
                .get(FeatureName.POSITION), 0.001);
        assertEquals(0.5, FeatureNormalizer.normalize(RAW, NormalizationContext.EMPTY, 1)
                .get(FeatureName.POSITION), 0.001);
        assertEquals(0.25, FeatureNormalizer.normalize(RAW, NormalizationContext.EMPTY, 3)
                .get(FeatureName.POSITION), 0.001);
    }

    @Test
    void emptyContextYieldsZeroForCorpusRelativeFeatures() {
        QueryDocumentFeatures f = FeatureNormalizer.normalize(RAW, NormalizationContext.EMPTY, 0);
        assertEquals(0.0, f.get(FeatureName.PAGE_RANK), 0.001);
        assertEquals(0.0, f.get(FeatureName.DOC_LENGTH), 0.001);
        // Document-local features still pass through.
        assertEquals(0.5, f.get(FeatureName.BM25), 0.001);
    }

    @Test
    void outputsAreClampedToUnitInterval() {
        QueryDocumentFeatures f = FeatureNormalizer.normalize(
                new RawFeatures(2.0, -3.0, 5.0, -0.2, 7.0, 1.5, 10, 0.0),
                new NormalizationContext(1.0, 5), 0);
        for (double value : f.values()) {
            assertTrue(value >= 0.0 && value <= 1.0, "Expected [0,1], got " + value);
        }
        assertEquals(1.0, f.get(FeatureName.BM25), 0.001);
        assertEquals(0.0, f.get(FeatureName.PAGE_RANK), 0.001);
    }

    @Test
    void rawFeaturesRoundTripThroughArray() {
        RawFeatures roundTripped = RawFeatures.of(RAW.toArray());
        assertEquals(RAW.bm25(), roundTripped.bm25(), 1e-12);
        assertEquals(RAW.pageRank(), roundTripped.pageRank(), 1e-12);
        assertEquals(RAW.titleMatch(), roundTripped.titleMatch(), 1e-12);
        assertEquals(RAW.urlMatch(), roundTripped.urlMatch(), 1e-12);
        assertEquals(RAW.termOverlap(), roundTripped.termOverlap(), 1e-12);
        assertEquals(RAW.semanticSimilarity(), roundTripped.semanticSimilarity(), 1e-12);
        assertEquals(RAW.docLength(), roundTripped.docLength(), 1e-12);
    }
}
