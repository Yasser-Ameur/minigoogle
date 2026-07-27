package com.minigoogle.query.trie;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for Trie prefix-search functionality. */
class TrieTest {

    @Test
    void testPrefixSearch() {
        Trie trie = new Trie();
        trie.insert("algorithm");
        trie.insert("algebra");
        trie.insert("alpine");
        trie.insert("apple");

        List<String> algMatches = trie.findPrefixes("alg");
        assertEquals(2, algMatches.size());
        assertTrue(algMatches.contains("algorithm"));
        assertTrue(algMatches.contains("algebra"));

        List<String> aMatches = trie.findPrefixes("a");
        assertEquals(4, aMatches.size());
    }
}
