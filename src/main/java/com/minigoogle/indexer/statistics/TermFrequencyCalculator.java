package com.minigoogle.indexer.statistics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Calculates term frequency counts for a list of tokens within a document. */
public class TermFrequencyCalculator {
    public Map<String, Integer> calculateFrequencies(List<String> tokens) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokens) {
            frequencies.put(token, frequencies.getOrDefault(token, 0) + 1);
        }
        return frequencies;
    }
}
