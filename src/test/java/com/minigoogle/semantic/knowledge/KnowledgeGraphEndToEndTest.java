package com.minigoogle.semantic.knowledge;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.demo.DemoDocuments;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the knowledge graph built over the demo corpus,
 * mirroring MiniGoogleApp.reindex's knowledge-graph wiring.
 */
class KnowledgeGraphEndToEndTest {

    private KnowledgeGraph buildGraph() {
        EntityExtractor extractor = new EntityExtractor(10);
        KnowledgeGraph graph = new KnowledgeGraph(8);
        List<ParsedDocument> docs = DemoDocuments.all();
        for (int i = 0; i < docs.size(); i++) {
            ParsedDocument doc = docs.get(i);
            graph.addDocument(i + 1, extractor.extract(doc.title(), doc.text()));
        }
        return graph;
    }

    @Test
    void testGraphBuiltOverDemoCorpus() {
        KnowledgeGraph graph = buildGraph();
        assertFalse(graph.entities().isEmpty(), "Demo corpus should yield entities");
        assertTrue(graph.entityCount() >= 10, "Expected a rich entity set");
    }

    @Test
    void testKnownEntitiesPresent() {
        KnowledgeGraph graph = buildGraph();
        assertTrue(graph.entities().contains("Java Programming Language"));
        assertTrue(graph.entities().contains("James Gosling"));
        assertTrue(graph.entities().contains("Apache Kafka"));
    }

    @Test
    void testEntitiesHaveDocumentCounts() {
        KnowledgeGraph graph = buildGraph();
        assertTrue(graph.documentCount("Java Programming Language") >= 1);
        assertTrue(graph.documentCount("Apache Kafka") >= 1);
    }

    @Test
    void testRelatedEntitiesPopulatedAndCapped() {
        KnowledgeGraph graph = buildGraph();
        // Java Programming Language co-occurs with other Java entities in its doc.
        List<KnowledgeGraph.RelatedEntity> related = graph.relatedEntities("Java Programming Language");
        assertFalse(related.isEmpty(), "Java entities should co-occur with others");
        assertTrue(related.size() <= 8, "Related entities must respect the cap");
    }

    @Test
    void testTopEntityHasMostDocMentions() {
        KnowledgeGraph graph = buildGraph();
        String top = graph.entities().stream()
                .max((a, b) -> {
                    int c = Integer.compare(graph.documentCount(a), graph.documentCount(b));
                    return c != 0 ? c : b.compareTo(a);
                })
                .orElseThrow();
        assertTrue(graph.documentCount(top) >= 1);
    }

    @Test
    void testRelatedEntitiesDeterministicOrder() {
        KnowledgeGraph graph = buildGraph();
        List<KnowledgeGraph.RelatedEntity> first = graph.relatedEntities("Java Programming Language");
        List<KnowledgeGraph.RelatedEntity> second = graph.relatedEntities("Java Programming Language");
        assertEquals(first, second);
    }
}
