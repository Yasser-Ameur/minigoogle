package com.minigoogle.query.bktree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class BKNode {
    /** Internal node storing a word and children keyed by Levenshtein distance. */
    String word;
    Map<Integer, BKNode> children = new HashMap<>();

    public BKNode(String word) {
        this.word = word;
    }
}

/**
 * BK-tree data structure for efficient fuzzy string matching / spell correction.
 * Stores words in a tree indexed by Levenshtein distance, enabling fast approximate
 * lookup of all words within a given edit distance of a query.
 */
public class BKTree {
    private BKNode root;

    public void insert(String word) {
        if (root == null) {
            root = new BKNode(word);
            return;
        }

        BKNode current = root;
        while (true) {
            int distance = computeLevenshteinDistance(current.word, word);
            if (distance == 0) return; // already exists

            BKNode next = current.children.get(distance);
            if (next == null) {
                current.children.put(distance, new BKNode(word));
                break;
            }
            current = next;
        }
    }

    public List<String> search(String query, int maxDistance) {
        List<String> results = new ArrayList<>();
        if (root != null) {
            searchRecursive(root, query, maxDistance, results);
        }
        return results;
    }

    private void searchRecursive(BKNode node, String query, int maxDistance, List<String> results) {
        int distance = computeLevenshteinDistance(node.word, query);

        if (distance <= maxDistance) {
            results.add(node.word);
        }

        int minDistance = distance - maxDistance;
        int maxDist = distance + maxDistance;

        for (int i = minDistance; i <= maxDist; i++) {
            BKNode child = node.children.get(i);
            if (child != null) {
                searchRecursive(child, query, maxDistance, results);
            }
        }
    }

    private int computeLevenshteinDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) {
            costs[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            int[] newCosts = new int[b.length() + 1];
            newCosts[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int match = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                int cost_replace = costs[j - 1] + match;
                int cost_insert = costs[j] + 1;
                int cost_delete = newCosts[j - 1] + 1;
                newCosts[j] = Math.min(Math.min(cost_insert, cost_delete), cost_replace);
            }
            costs = newCosts;
        }
        return costs[b.length()];
    }
}
