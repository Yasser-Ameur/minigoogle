package com.minigoogle.indexer.tokenizer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for Tokenizer functionality. */
class TokenizerTest {
    @Test
    void testBasicTokenization() {
        Tokenizer tokenizer = new Tokenizer();
        List<String> tokens = tokenizer.tokenize("The quick brown fox.");
        assertEquals(List.of("The", "quick", "brown", "fox"), tokens);
    }
}
