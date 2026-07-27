package com.minigoogle.query.trie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class TrieNode {
    /** Internal node mapping characters to child nodes and marking word boundaries. */
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isEndOfWord;
}

/**
 * Prefix tree for autocomplete and prefix-based search.
 * Supports insertion of words and efficient retrieval of all words sharing a given prefix
 * via depth-first traversal from the matching node.
 */
public class Trie {
    private final TrieNode root;

    public Trie() {
        root = new TrieNode();
    }

    public void insert(String word) {
        TrieNode current = root;
        for (char l : word.toCharArray()) {
            current = current.children.computeIfAbsent(l, k -> new TrieNode());
        }
        current.isEndOfWord = true;
    }

    public List<String> findPrefixes(String prefix) {
        List<String> results = new ArrayList<>();
        TrieNode current = root;
        for (char l : prefix.toCharArray()) {
            current = current.children.get(l);
            if (current == null) {
                return results;
            }
        }
        
        dfs(current, prefix, results);
        return results;
    }

    private void dfs(TrieNode node, String currentPrefix, List<String> results) {
        if (node.isEndOfWord) {
            results.add(currentPrefix);
        }
        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            dfs(entry.getValue(), currentPrefix + entry.getKey(), results);
        }
    }
}
