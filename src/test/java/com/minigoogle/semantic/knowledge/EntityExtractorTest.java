package com.minigoogle.semantic.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for deterministic proper-noun entity extraction.
 */
class EntityExtractorTest {

    @Test
    void testExtractsCapitalizedMultiWordPhrases() {
        EntityExtractor extractor = new EntityExtractor(20);
        String text = "James Gosling created the Java Programming Language at "
                + "Sun Microsystems in 1995.";
        List<String> entities = extractor.extract("Java", text);
        assertTrue(entities.contains("James Gosling"));
        assertTrue(entities.contains("Java Programming Language"));
        assertTrue(entities.contains("Sun Microsystems"));
    }

    @Test
    void testSkipsCommonSentenceWords() {
        EntityExtractor extractor = new EntityExtractor(20);
        List<String> entities = extractor.extract("Title", "The This These Those are generic words.");
        assertFalse(entities.contains("The"));
        assertFalse(entities.contains("This"));
        assertFalse(entities.contains("These"));
        assertFalse(entities.contains("Those"));
    }

    @Test
    void testTitleOccurrencesWeightedHigher() {
        EntityExtractor extractor = new EntityExtractor(20);
        List<String> entities = extractor.extract(
                "Apache Kafka",
                "Kafka is a distributed system. Kafka handles streams.");
        assertEquals("Apache Kafka", entities.get(0));
    }

    @Test
    void testMaxEntitiesPerDocCapsResults() {
        EntityExtractor extractor = new EntityExtractor(2);
        String text = "Raft Consensus and Paxos Algorithm and Quorum Protocol are all distributed ideas.";
        List<String> entities = extractor.extract("Title", text);
        assertTrue(entities.size() <= 2);
    }

    @Test
    void testEmptyText() {
        EntityExtractor extractor = new EntityExtractor();
        assertTrue(extractor.extract(null, null).isEmpty());
        assertTrue(extractor.extract("", "").isEmpty());
    }

    @Test
    void testOrderingIsDeterministic() {
        EntityExtractor extractor = new EntityExtractor(20);
        String text = "TensorFlow Graph and TensorFlow Engine and TensorFlow Core.";
        List<String> first = extractor.extract("TensorFlow", text);
        List<String> second = extractor.extract("TensorFlow", text);
        assertEquals(first, second);
    }

    @Test
    void testInvalidCapThrows() {
        assertThrows(IllegalArgumentException.class, () -> new EntityExtractor(0));
    }
}
