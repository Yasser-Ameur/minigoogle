package com.minigoogle.indexer.tokenizer;

import java.util.ArrayList;
import java.util.List;

/** Splits text into alphanumeric tokens by delimiting on non-alphanumeric characters. */
public class Tokenizer {
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }

        StringBuilder currentWord = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isAlphabetic(c) || Character.isDigit(c)) {
                currentWord.append(c);
            } else {
                if (!currentWord.isEmpty()) {
                    tokens.add(currentWord.toString());
                    currentWord.setLength(0);
                }
            }
        }
        
        if (!currentWord.isEmpty()) {
            tokens.add(currentWord.toString());
        }

        return tokens;
    }
}
