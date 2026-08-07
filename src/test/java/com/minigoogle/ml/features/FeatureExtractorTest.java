package com.minigoogle.ml.features;

import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the query-document feature extractor. */
class FeatureExtractorTest {

    private FeatureExtractor extractor(Map<Integer, String> urls,
                                       Map<Integer, String> titles,
                                       Map<Integer, String> bodies,
                                       Map<Integer, Integer> lengths,
                                       Map<Integer, Double> pageRanks,
                                       VectorIndex vectorIndex,
                                       EmbeddingGenerator generator) {
        return new FeatureExtractor(urls, titles, bodies, lengths, pageRanks, vectorIndex, generator);
    }

    private Map<Integer, String> stringMap(String... entries) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(Integer.parseInt(entries[i]), entries[i + 1]);
        }
        return map;
    }

    @Test
    void testFeaturesBoundedAndMeaningful() {
        Map<Integer, String> urls = stringMap(
                "1", "http://example.com/java", "2", "http://example.com/python");
        Map<Integer, String> titles = stringMap(
                "1", "Java Programming Guide", "2", "Python Scripting");
        Map<Integer, String> bodies = stringMap(
                "1", "java java java programming guide for java developers",
                "2", "python scripting for automation");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 100);
        lengths.put(2, 50);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.8);
        pageRanks.put(2, 0.4);

        FeatureExtractor extractor = extractor(urls, titles, bodies, lengths, pageRanks, null, null);
        QueryDocumentFeatures f = extractor.extract("java", 1, 0);

        for (double value : f.values()) {
            assertTrue(value >= 0.0 && value <= 1.0, "Feature should be in [0,1]: " + value);
        }

        // TF-saturated lexical score for a body with 4 occurrences of "java".
        assertEquals(1.0 - 1.0 / 5.0, f.get(FeatureName.BM25), 0.001);
        assertEquals(1.0, f.get(FeatureName.PAGE_RANK), 0.001);
        assertEquals(1.0, f.get(FeatureName.TITLE_MATCH), 0.001);
        assertEquals(1.0, f.get(FeatureName.URL_MATCH), 0.001);
        assertEquals(1.0, f.get(FeatureName.TERM_OVERLAP), 0.001);
        assertEquals(0.0, f.get(FeatureName.SEMANTIC_SIMILARITY), 0.001);
        assertEquals(1.0, f.get(FeatureName.DOC_LENGTH), 0.001);
        assertEquals(1.0, f.get(FeatureName.POSITION), 0.001);
    }

    @Test
    void testNonMatchingDocumentScoresZero() {
        Map<Integer, String> urls = stringMap("1", "http://example.com/a", "2", "http://example.com/b");
        Map<Integer, String> titles = stringMap("1", "Alpha", "2", "Beta");
        Map<Integer, String> bodies = stringMap("1", "alpha alpha", "2", "beta beta beta");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 10);
        lengths.put(2, 20);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.9);
        pageRanks.put(2, 0.3);

        FeatureExtractor extractor = extractor(urls, titles, bodies, lengths, pageRanks, null, null);
        QueryDocumentFeatures f = extractor.extract("beta", 1, 0);

        assertEquals(0.0, f.get(FeatureName.BM25), 0.001);
        assertEquals(0.0, f.get(FeatureName.TITLE_MATCH), 0.001);
        assertEquals(0.0, f.get(FeatureName.URL_MATCH), 0.001);
        assertEquals(0.0, f.get(FeatureName.TERM_OVERLAP), 0.001);
        // PageRank is still present and normalized.
        assertEquals(1.0, f.get(FeatureName.PAGE_RANK), 0.001);
    }

    @Test
    void testPositionFeatureAndRankedDocumentOverload() {
        Map<Integer, String> urls = stringMap("1", "http://example.com/a");
        Map<Integer, String> titles = stringMap("1", "Alpha");
        Map<Integer, String> bodies = stringMap("1", "alpha");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 10);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.5);

        FeatureExtractor extractor = extractor(urls, titles, bodies, lengths, pageRanks, null, null);
        RankedDocument doc = new RankedDocument(1, "http://example.com/a", "Alpha", 0.7, 0.5, 0.6, "snip");

        QueryDocumentFeatures byId = extractor.extract("alpha", 1, 3);
        QueryDocumentFeatures byDoc = extractor.extract("alpha", doc, 3);

        assertEquals(0.25, byId.get(FeatureName.POSITION), 0.001);
        assertArrayEquals(byId.values(), byDoc.values(), "Serving and training feature paths must agree");
    }

    @Test
    void testSemanticFeatureFromVectorIndex() {
        Map<Integer, String> urls = stringMap("1", "http://example.com/java", "2", "http://example.com/cars");
        Map<Integer, String> titles = stringMap("1", "Java", "2", "Cars");
        Map<Integer, String> bodies = stringMap("1", "java java java", "2", "cars cars cars");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 10);
        lengths.put(2, 10);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.5);
        pageRanks.put(2, 0.5);

        EmbeddingGenerator generator = new EmbeddingGenerator(16);
        VectorIndex vectorIndex = new VectorIndex(16);
        vectorIndex.add(1, generator.embed("java programming"));
        vectorIndex.add(2, generator.embed("automobile cars driving"));

        FeatureExtractor extractor = extractor(urls, titles, bodies, lengths, pageRanks, vectorIndex, generator);
        QueryDocumentFeatures javaDoc = extractor.extract("java", 1, 0);
        QueryDocumentFeatures carDoc = extractor.extract("java", 2, 0);

        assertTrue(javaDoc.get(FeatureName.SEMANTIC_SIMILARITY) > carDoc.get(FeatureName.SEMANTIC_SIMILARITY),
                "Semantic similarity should favor the vocabulary-matching document");
        assertEquals(0.0, extractor.extract("java", 99, 0).get(FeatureName.SEMANTIC_SIMILARITY), 0.001);
    }

    @Test
    void testExtractionIsDeterministic() {
        Map<Integer, String> urls = stringMap("1", "http://example.com/a");
        Map<Integer, String> titles = stringMap("1", "Alpha");
        Map<Integer, String> bodies = stringMap("1", "alpha alpha");
        Map<Integer, Integer> lengths = new HashMap<>();
        lengths.put(1, 10);
        Map<Integer, Double> pageRanks = new HashMap<>();
        pageRanks.put(1, 0.5);

        FeatureExtractor extractor = extractor(urls, titles, bodies, lengths, pageRanks, null, null);
        assertArrayEquals(extractor.extract("alpha", 1, 0).values(),
                extractor.extract("alpha", 1, 0).values());
    }
}
