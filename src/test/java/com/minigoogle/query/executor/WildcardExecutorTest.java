package com.minigoogle.query.executor;

import com.minigoogle.indexer.inverted.InvertedIndex;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.trie.Trie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for WildcardExecutor functionality. */
class WildcardExecutorTest {

    private Trie trie;
    private InvertedIndex index;
    private WildcardExecutor executor;

    @BeforeEach
    void setUp() {
        trie = new Trie();
        index = new InvertedIndex();

        // Insert terms into trie and index
        String[] terms = {"algorithm", "algebra", "alpine", "apple", "application"};
        int docId = 1;
        for (String term : terms) {
            trie.insert(term);
            index.addPosting(term, new Posting(docId++, 1, List.of(0)));
        }

        executor = new WildcardExecutor(trie, index);
    }

    @Test
    void testWildcardWithAlgPrefix() {
        PostingList result = executor.execute("alg*");
        // Should match "algebra", "algorithm"
        assertNotNull(result);
        assertEquals(2, result.getPostings().size());
    }

    @Test
    void testWildcardWithApPrefix() {
        PostingList result = executor.execute("ap*");
        // Should match "apple", "application"
        assertNotNull(result);
        assertEquals(2, result.getPostings().size());
    }

    @Test
    void testWildcardWithFullMatch() {
        PostingList result = executor.execute("apple*");
        assertNotNull(result);
        assertEquals(1, result.getPostings().size());
    }

    @Test
    void testWildcardNoMatch() {
        PostingList result = executor.execute("xyz*");
        assertNotNull(result);
        assertEquals(0, result.getPostings().size());
    }

    @Test
    void testIsWildcard() {
        assertTrue(WildcardExecutor.isWildcard("algo*"));
        assertFalse(WildcardExecutor.isWildcard("algo"));
        assertFalse(WildcardExecutor.isWildcard(null));
    }
}
