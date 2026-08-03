package com.minigoogle.semantic.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A lightweight knowledge graph over the indexed corpus.
 *
 * <p>Entities are nodes; two entities that co-occur in the same document form
 * a weighted edge (the edge weight is the number of documents in which both
 * appear). Each entity also retains the set of documents that mention it.
 * Related entities are returned ordered by co-occurrence weight descending
 * and then alphabetically, capped at {@code maxRelated}.</p>
 */
public final class KnowledgeGraph {

    private final int maxRelated;
    private final Map<String, Set<Integer>> entityDocs = new HashMap<>();
    private final Map<String, Map<String, Integer>> cooccurrence = new HashMap<>();

    /**
     * Creates a graph that returns up to 8 related entities.
     */
    public KnowledgeGraph() {
        this(8);
    }

    /**
     * Creates a graph with a per-entity related cap.
     *
     * @param maxRelated Maximum related entities returned per lookup (>= 1).
     */
    public KnowledgeGraph(int maxRelated) {
        if (maxRelated < 1) {
            throw new IllegalArgumentException("maxRelated must be >= 1");
        }
        this.maxRelated = maxRelated;
    }

    /**
     * Records the entities present in a single document, updating doc memberships
     * and co-occurrence edges.
     *
     * @param docId    The document id (1-based).
     * @param entities The entities extracted from the document.
     */
    public void addDocument(int docId, List<String> entities) {
        Set<String> unique = new LinkedHashSet<>(entities);
        for (String entity : unique) {
            entityDocs.computeIfAbsent(entity, k -> new LinkedHashSet<>()).add(docId);
        }
        List<String> ordered = new ArrayList<>(unique);
        for (int i = 0; i < ordered.size(); i++) {
            String a = ordered.get(i);
            for (int j = i + 1; j < ordered.size(); j++) {
                String b = ordered.get(j);
                Map<String, Integer> edges = cooccurrence.computeIfAbsent(a, k -> new HashMap<>());
                edges.merge(b, 1, Integer::sum);
                Map<String, Integer> reverse = cooccurrence.computeIfAbsent(b, k -> new HashMap<>());
                reverse.merge(a, 1, Integer::sum);
            }
        }
    }

    /**
     * Returns the number of documents mentioning the entity.
     *
     * @param entity The entity name.
     * @return Document count.
     */
    public int documentCount(String entity) {
        return entityDocs.getOrDefault(entity, Set.of()).size();
    }

    /**
     * Returns the ids of documents mentioning the entity.
     *
     * @param entity The entity name.
     * @return A set of document ids (possibly empty).
     */
    public Set<Integer> documentIds(String entity) {
        return entityDocs.getOrDefault(entity, Set.of());
    }

    /**
     * Returns the entities most frequently co-occurring with the given entity,
     * capped at {@code maxRelated}.
     *
     * @param entity The entity name.
     * @return A ranked list of related entities.
     */
    public List<RelatedEntity> relatedEntities(String entity) {
        Map<String, Integer> edges = cooccurrence.get(entity);
        if (edges == null) {
            return List.of();
        }
        List<RelatedEntity> related = new ArrayList<>();
        for (Map.Entry<String, Integer> e : edges.entrySet()) {
            related.add(new RelatedEntity(e.getKey(), e.getValue()));
        }
        related.sort(Comparator.comparingInt(RelatedEntity::weight).reversed()
                .thenComparing(RelatedEntity::entity));
        if (related.size() > maxRelated) {
            return related.subList(0, maxRelated);
        }
        return related;
    }

    /**
     * Total number of distinct entities in the graph.
     *
     * @return Entity count.
     */
    public int entityCount() {
        return entityDocs.size();
    }

    /**
     * Returns all distinct entities in the graph.
     *
     * @return A set of entity names (possibly empty).
     */
    public Set<String> entities() {
        return entityDocs.keySet();
    }

    /**
     * A related entity along with its co-occurrence weight.
     */
    public record RelatedEntity(String entity, int weight) {
    }
}
