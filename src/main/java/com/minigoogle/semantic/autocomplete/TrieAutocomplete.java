package com.minigoogle.semantic.autocomplete;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Trie-based autocomplete provider for prefix queries.
 *
 * <p>Inserts words into a trie structure and supports efficient prefix-based
 * retrieval with configurable result limits.</p>
 */
public class TrieAutocomplete {

    private final TrieNode root;
    private int wordCount;
    private final Map<String, Integer> termFrequencies;

    /**
     * Creates an empty trie autocomplete structure.
     */
    public TrieAutocomplete() {
        this.root = new TrieNode();
        this.wordCount = 0;
        this.termFrequencies = new HashMap<>();
    }

    /**
     * Creates a trie autocomplete structure with term frequency ranking.
     *
     * @param termFrequencies Map of term → document frequency for relevance ranking.
     */
    public TrieAutocomplete(Map<String, Integer> termFrequencies) {
        this.root = new TrieNode();
        this.wordCount = 0;
        this.termFrequencies = termFrequencies != null ? termFrequencies : new HashMap<>();
    }

    /**
     * Inserts a word into the trie.
     *
     * @param word The word to insert. Must not be null or empty.
     */
    public void addWord(String word) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("word must not be null or empty");
        }
        TrieNode current = root;
        for (char c : word.toLowerCase().toCharArray()) {
            current.children.putIfAbsent(c, new TrieNode());
            current = current.children.get(c);
        }
        if (!current.isEndOfWord) {
            current.isEndOfWord = true;
            wordCount++;
        }
        current.fullWord = word;
    }

    /**
     * Returns up to {@code maxResults} words that start with the given prefix.
     *
     * @param prefix     The prefix to search for.
     * @param maxResults Maximum number of results to return.
     * @return A list of matching words.
     */
    public List<String> autocomplete(String prefix, int maxResults) {
        TrieNode current = root;

        for (char c : prefix.toLowerCase().toCharArray()) {
            current = current.children.get(c);
            if (current == null) {
                return List.of();
            }
        }

        return collectAndRank(current, prefix, maxResults);
    }

    /**
     * Checks whether an exact word exists in the trie.
     *
     * @param word The word to look up.
     * @return {@code true} if the word was previously inserted.
     */
    public boolean contains(String word) {
        if (word == null || word.isEmpty()) return false;
        TrieNode current = root;
        for (char c : word.toLowerCase().toCharArray()) {
            current = current.children.get(c);
            if (current == null) return false;
        }
        return current.isEndOfWord;
    }

    /**
     * Returns the number of words stored in the trie.
     *
     * @return The word count.
     */
    public int size() {
        return wordCount;
    }

    private void collectWords(TrieNode node, List<String> results, int maxResults) {
        if (node.isEndOfWord) {
            results.add(node.fullWord);
        }
        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            collectWords(entry.getValue(), results, maxResults);
        }
    }

    private List<String> collectAndRank(TrieNode prefixNode, String prefix, int maxResults) {
        List<String> candidates = new ArrayList<>();
        collectWords(prefixNode, candidates, maxResults * 4);

        double prefixLen = prefix.length();

        PriorityQueue<String> heap = new PriorityQueue<>(
            Comparator.comparingDouble((String w) -> scoreWord(w, prefixLen)));

        for (String word : candidates) {
            heap.offer(word);
            if (heap.size() > maxResults) {
                heap.poll();
            }
        }

        List<String> ranked = new ArrayList<>(heap);
        ranked.sort(Comparator.comparingDouble((String w) -> scoreWord(w, prefixLen)).reversed());
        return ranked;
    }

    private double scoreWord(String word, double prefixLen) {
        int freq = termFrequencies.getOrDefault(word.toLowerCase(), 0);
        double prefixRatio = prefixLen / word.length();
        return Math.log(1 + freq) * prefixRatio;
    }

    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isEndOfWord;
        String fullWord;
    }
}
