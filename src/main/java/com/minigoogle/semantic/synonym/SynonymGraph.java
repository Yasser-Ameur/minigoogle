package com.minigoogle.semantic.synonym;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * An undirected graph representing synonym relationships between terms.
 *
 * <p>Terms are connected bidirectionally. Traversal uses BFS with a maximum
 * depth of 2 to limit expansion scope and avoid overly broad synonym chains.</p>
 */
public class SynonymGraph {

    private final Map<String, Set<String>> adjacency;

    /**
     * Creates an empty synonym graph.
     */
    public SynonymGraph() {
        this.adjacency = new HashMap<>();
    }

    /**
     * Adds a bidirectional synonym relationship between two terms.
     *
     * @param term1 The first term.
     * @param term2 The second term.
     */
    public void addSynonym(String term1, String term2) {
        String t1 = term1.toLowerCase(Locale.ROOT);
        String t2 = term2.toLowerCase(Locale.ROOT);
        adjacency.computeIfAbsent(t1, k -> new HashSet<>()).add(t2);
        adjacency.computeIfAbsent(t2, k -> new HashSet<>()).add(t1);
    }

    /**
     * Returns all synonyms reachable from the given term within depth 2.
     *
     * @param term The term to look up.
     * @return A set of synonym terms (not including the original term).
     */
    public Set<String> getSynonyms(String term) {
        String start = term.toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String[]> queue = new ArrayDeque<>();

        visited.add(start);
        queue.offer(new String[]{start, "0"});

        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            String node = current[0];
            int depth = Integer.parseInt(current[1]);

            if (depth >= 2) continue;

            Set<String> neighbors = adjacency.getOrDefault(node, Set.of());
            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    result.add(neighbor);
                    queue.offer(new String[]{neighbor, String.valueOf(depth + 1)});
                }
            }
        }

        return result;
    }

    /**
     * Checks whether two terms are synonyms (directly connected or reachable within depth 2).
     *
     * @param term1 The first term.
     * @param term2 The second term.
     * @return {@code true} if they are synonyms.
     */
    public boolean areSynonyms(String term1, String term2) {
        String t1 = term1.toLowerCase(Locale.ROOT);
        String t2 = term2.toLowerCase(Locale.ROOT);
        if (t1.equals(t2)) return true;
        return getSynonyms(t1).contains(t2);
    }

    /**
     * Returns the number of distinct terms in the graph.
     *
     * @return The term count.
     */
    public int size() {
        return adjacency.size();
    }
}
