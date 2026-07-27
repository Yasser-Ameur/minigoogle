package com.minigoogle.indexer.stopwords;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Filters out common English stop words that carry little discriminative value. */
public class StopWordFilter {
    
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", 
        "into", "is", "it", "no", "not", "of", "on", "or", "such", "that", "the", 
        "their", "then", "there", "these", "they", "this", "to", "was", "will", "with"
    ));

    public boolean isStopWord(String word) {
        if (word == null) return false;
        return STOP_WORDS.contains(word); // Assumes word is already lowercased
    }
}
