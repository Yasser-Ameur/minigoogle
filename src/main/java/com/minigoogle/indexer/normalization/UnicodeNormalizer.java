package com.minigoogle.indexer.normalization;

import java.text.Normalizer;

/** Applies Unicode NFKC normalization to standardize text representation. */
public class UnicodeNormalizer {
    public String normalize(String text) {
        if (text == null) return null;
        return Normalizer.normalize(text, Normalizer.Form.NFKC);
    }
}
