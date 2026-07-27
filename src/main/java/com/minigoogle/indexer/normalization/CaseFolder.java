package com.minigoogle.indexer.normalization;

import java.util.Locale;

/** Lowercases all characters to normalize text for case-insensitive matching. */
public class CaseFolder {
    public String fold(String text) {
        if (text == null) return null;
        return text.toLowerCase(Locale.ROOT);
    }
}
