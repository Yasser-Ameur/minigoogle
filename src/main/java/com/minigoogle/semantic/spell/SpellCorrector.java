package com.minigoogle.semantic.spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Suggests corrections for misspelled query terms using Levenshtein distance.
 *
 * <p>Maintains an in-memory vocabulary and scores each candidate word by its
 * Levenshtein similarity to the input. Words exceeding a distance threshold
 * of 2 are rejected.</p>
 */
public class SpellCorrector {

    private static final int MAX_DISTANCE = 2;

    private final Set<String> vocabulary;

    /**
     * Creates a spell corrector backed by the given vocabulary.
     *
     * @param vocabulary The set of known correct words.
     */
    public SpellCorrector(Set<String> vocabulary) {
        this.vocabulary = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        this.vocabulary.addAll(vocabulary);
    }

    /**
     * Creates a spell corrector with an empty vocabulary.
     */
    public SpellCorrector() {
        this.vocabulary = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * Returns the best correction for a word, or the original if no close match is found.
     *
     * @param word The potentially misspelled word.
     * @return The best matching correction, or the original word.
     */
    public String correct(String word) {
        List<String> suggestions = suggest(word, 1);
        return suggestions.isEmpty() ? word : suggestions.getFirst();
    }

    /**
     * Returns up to {@code maxSuggestions} corrections ranked by similarity.
     *
     * @param word            The potentially misspelled word.
     * @param maxSuggestions   Maximum number of suggestions to return.
     * @return A list of suggested corrections, ordered by descending similarity.
     */
    public List<String> suggest(String word, int maxSuggestions) {
        String lower = word.toLowerCase(Locale.ROOT);
        PriorityQueue<String[]> heap = new PriorityQueue<>(
                Comparator.comparingInt(s -> Integer.parseInt(s[1])));

        for (String vocabWord : vocabulary) {
            int dist = Levenshtein.distance(lower, vocabWord.toLowerCase(Locale.ROOT));
            if (dist <= MAX_DISTANCE) {
                heap.offer(new String[]{vocabWord, String.valueOf(dist)});
                if (heap.size() > maxSuggestions) {
                    heap.poll();
                }
            }
        }

        List<String> results = new ArrayList<>();
        while (!heap.isEmpty()) {
            results.addFirst(heap.poll()[0]);
        }
        return results;
    }

    /**
     * Adds a word to the vocabulary.
     *
     * @param word The word to add.
     */
    public void addWord(String word) {
        vocabulary.add(word.toLowerCase(Locale.ROOT));
    }
}
