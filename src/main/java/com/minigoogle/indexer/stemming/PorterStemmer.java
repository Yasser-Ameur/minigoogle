package com.minigoogle.indexer.stemming;

/**
 * A simplified implementation of the Porter Stemmer algorithm.
 * Note: A full implementation of Porter stemmer is typically several hundred lines. 
 * This contains the basic logic for demonstration, matching common suffixes.
 */
public class PorterStemmer {
    public String stem(String word) {
        if (word == null || word.length() <= 2) {
            return word;
        }

        String w = word;
        
        // Step 1a
        if (w.endsWith("sses")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("ies")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("ss")) {
            // do nothing
        } else if (w.endsWith("s")) {
            w = w.substring(0, w.length() - 1);
        }

        // Basic Step 1b (simplified)
        if (w.endsWith("eed")) {
            w = w.substring(0, w.length() - 1);
        } else if (w.endsWith("ed")) {
            w = w.substring(0, w.length() - 2);
        } else if (w.endsWith("ing")) {
            w = w.substring(0, w.length() - 3);
        }
        
        // Very simplified additional steps for 'runner' -> 'run' etc.
        if (w.endsWith("er")) {
            w = w.substring(0, w.length() - 2);
        }
        
        // Fix double consonants e.g., 'runn' -> 'run'
        if (w.length() > 2 && w.charAt(w.length() - 1) == w.charAt(w.length() - 2)) {
            char c = w.charAt(w.length() - 1);
            if (c != 'l' && c != 's' && c != 'z') {
                w = w.substring(0, w.length() - 1);
            }
        }

        return w;
    }
}
