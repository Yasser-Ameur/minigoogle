package com.minigoogle.semantic.expansion;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.semantic.synonym.SynonymGraph;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the corpus-derived PMI thesaurus builder.
 */
class PmiThesaurusBuilderTest {

    private ParsedDocument doc(String title, String text) {
        return new ParsedDocument(
                UUID.randomUUID(),
                URI.create("http://example.com/" + UUID.randomUUID()),
                title, text, List.of(), Instant.now());
    }

    /**
     * Builds a corpus where java/kafka co-occur in 20% of windows (PMI ~1.6)
     * and zebra never co-occurs with java.
     */
    private List<ParsedDocument> corpus() {
        List<ParsedDocument> docs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            docs.add(doc("d" + i, "java kafka ".repeat(15)));
        }
        for (int i = 0; i < 8; i++) {
            docs.add(doc("z" + i, "zebra ".repeat(30)));
        }
        return docs;
    }

    @Test
    void testCooccurringTermsBecomeSynonyms() {
        SynonymGraph graph = new PmiThesaurusBuilder().build(corpus());
        assertTrue(graph.getSynonyms("java").contains("kafka"));
        assertTrue(graph.getSynonyms("kafka").contains("java"));
    }

    @Test
    void testNonCooccurringTermsAreNotConnected() {
        SynonymGraph graph = new PmiThesaurusBuilder().build(corpus());
        assertFalse(graph.getSynonyms("java").contains("zebra"));
    }

    @Test
    void testBuildIsDeterministic() {
        PmiThesaurusBuilder builder = new PmiThesaurusBuilder();
        SynonymGraph first = builder.build(corpus());
        SynonymGraph second = builder.build(corpus());
        assertEquals(first.getSynonyms("java"), second.getSynonyms("java"));
        assertEquals(first.size(), second.size());
    }

    @Test
    void testExpanderUsesCorpusGraphOnly() {
        SynonymGraph graph = new PmiThesaurusBuilder().build(corpus());
        QueryExpander expander = new QueryExpander(graph);
        assertTrue(expander.expand("java", 4).contains("kafka"));
        // No hard-coded defaults should leak into a corpus-derived expander.
        assertEquals(List.of("car"), expander.expand("car", 4));
    }

    @Test
    void testEmptyCorpusYieldsEmptyGraph() {
        SynonymGraph graph = new PmiThesaurusBuilder().build(List.of());
        assertEquals(0, graph.size());
        assertTrue(graph.getSynonyms("java").isEmpty());
    }

    @Test
    void testInvalidParametersThrow() {
        assertThrows(IllegalArgumentException.class, () -> new PmiThesaurusBuilder(1, 1.0, 5));
        assertThrows(IllegalArgumentException.class, () -> new PmiThesaurusBuilder(10, -0.5, 5));
        assertThrows(IllegalArgumentException.class, () -> new PmiThesaurusBuilder(10, 1.0, 0));
    }

    @Test
    void testMaxNeighborsCapsExpansion() {
        // Title-free corpus: java co-occurs only with kafka, so a cap of 1
        // neighbor must yield exactly one java synonym (no reverse-edge noise).
        List<ParsedDocument> corpus = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            corpus.add(new ParsedDocument(
                    UUID.randomUUID(), URI.create("http://example.com/" + i), null,
                    "java kafka ".repeat(15), List.of(), Instant.now()));
        }
        for (int i = 0; i < 8; i++) {
            corpus.add(new ParsedDocument(
                    UUID.randomUUID(), URI.create("http://example.com/z" + i), null,
                    "zebra ".repeat(30), List.of(), Instant.now()));
        }
        SynonymGraph graph = new PmiThesaurusBuilder(10, 0.5, 1).build(corpus);
        assertEquals(Set.of("kafka"), graph.getSynonyms("java"));
    }

    @Test
    void testQueryExpanderIsSubstitutable() {
        // The no-arg expander still loads defaults.
        QueryExpander defaults = new QueryExpander();
        Set<String> carSynonyms = defaults.expand("car", 4).stream()
                .filter(t -> !t.equals("car"))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(carSynonyms.contains("automobile"));
    }
}
