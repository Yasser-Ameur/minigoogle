package com.minigoogle.storage.cache;

public record DictionaryEntry(
        String term,
        long postingOffset,
        int postingLength,
        long documentFrequency
) {
}
