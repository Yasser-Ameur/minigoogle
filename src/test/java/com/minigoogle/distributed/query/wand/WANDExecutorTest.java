package com.minigoogle.distributed.query.wand;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.result.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for WAND executor functionality. */
class WANDExecutorTest {

    @Test
    void testBasicWand() {
        WANDExecutor wand = new WANDExecutor(2);

        PostingList list1 = new PostingList(List.of(
                new Posting(1, 5, List.of(0)),
                new Posting(2, 3, List.of(1)),
                new Posting(3, 1, List.of(2))
        ));
        PostingList list2 = new PostingList(List.of(
                new Posting(1, 4, List.of(0)),
                new Posting(2, 6, List.of(1)),
                new Posting(4, 2, List.of(3))
        ));

        List<SearchResult> results = wand.execute(
                List.of(list1, list2),
                List.of(5.0, 6.0)
        );

        assertEquals(2, results.size());
        // Doc 2 should be highest (3+6=9), doc 1 second (5+4=9... actually same)
        // Both are scored, top-2 returned
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void testEmptyPostingLists() {
        WANDExecutor wand = new WANDExecutor(10);
        List<SearchResult> results = wand.execute(List.of(), List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void testSinglePostingList() {
        WANDExecutor wand = new WANDExecutor(1);
        PostingList list = new PostingList(List.of(
                new Posting(1, 5, List.of(0)),
                new Posting(2, 3, List.of(1))
        ));

        List<SearchResult> results = wand.execute(List.of(list), List.of(5.0));
        assertEquals(1, results.size());
        assertEquals(5.0, results.get(0).score());
    }
}
