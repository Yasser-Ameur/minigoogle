package com.minigoogle.semantic.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the corpus knowledge graph.
 */
class KnowledgeGraphTest {

    @Test
    void testAddDocumentTracksDocMemberships() {
        KnowledgeGraph graph = new KnowledgeGraph();
        graph.addDocument(1, List.of("Java", "Kafka"));
        graph.addDocument(2, List.of("Java"));
        assertEquals(2, graph.documentCount("Java"));
        assertEquals(1, graph.documentCount("Kafka"));
        assertEquals(Set.of(1, 2), graph.documentIds("Java"));
        assertEquals(Set.of(1), graph.documentIds("Kafka"));
    }

    @Test
    void testCooccurrenceWeightsAccumulate() {
        KnowledgeGraph graph = new KnowledgeGraph();
        graph.addDocument(1, List.of("Java", "Kafka", "Raft"));
        graph.addDocument(2, List.of("Java", "Kafka"));
        List<KnowledgeGraph.RelatedEntity> related = graph.relatedEntities("Java");
        // Kafka co-occurs with Java in both docs, Raft only in doc 1.
        assertEquals("Kafka", related.get(0).entity());
        assertEquals(2, related.get(0).weight());
    }

    @Test
    void testDuplicateEntitiesInDocCountOnce() {
        KnowledgeGraph graph = new KnowledgeGraph();
        graph.addDocument(1, List.of("Java", "Java", "Kafka"));
        assertEquals(1, graph.documentCount("Java"));
        assertEquals(1, graph.relatedEntities("Java").get(0).weight());
    }

    @Test
    void testRelatedEntitiesCapped() {
        KnowledgeGraph graph = new KnowledgeGraph(2);
        graph.addDocument(1, List.of("A", "B", "C", "D"));
        List<KnowledgeGraph.RelatedEntity> related = graph.relatedEntities("A");
        assertEquals(2, related.size());
    }

    @Test
    void testRelatedSortedByWeightThenAlpha() {
        KnowledgeGraph graph = new KnowledgeGraph(10);
        graph.addDocument(1, List.of("X", "B", "C"));
        graph.addDocument(2, List.of("X", "B"));
        List<KnowledgeGraph.RelatedEntity> related = graph.relatedEntities("X");
        // B weight 2, C weight 1.
        assertEquals("B", related.get(0).entity());
        assertEquals("C", related.get(1).entity());
    }

    @Test
    void testUnknownEntityReturnsEmpty() {
        KnowledgeGraph graph = new KnowledgeGraph();
        assertTrue(graph.relatedEntities("Missing").isEmpty());
        assertEquals(0, graph.documentCount("Missing"));
        assertTrue(graph.documentIds("Missing").isEmpty());
    }

    @Test
    void testEntitiesReturnsAllNodes() {
        KnowledgeGraph graph = new KnowledgeGraph();
        graph.addDocument(1, List.of("Java", "Kafka"));
        graph.addDocument(2, List.of("Raft"));
        assertEquals(Set.of("Java", "Kafka", "Raft"), graph.entities());
        assertEquals(3, graph.entityCount());
    }

    @Test
    void testInvalidCapThrows() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeGraph(0));
    }
}
