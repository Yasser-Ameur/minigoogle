package com.minigoogle.storage.dictionary;

/**
 * Record storing a term, its file offset, and document frequency.
 */
public record DictionaryEntry(String term, long postingOffset, int documentFrequency) {}
