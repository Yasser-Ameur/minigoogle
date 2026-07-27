package com.minigoogle.distributed.query.wand;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.result.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for Block-Max WAND query execution algorithm. */
class BlockMaxWANDTest {

    @Test
    void testBasicBlockMaxWand() {
        BlockMaxWAND wand = new BlockMaxWAND(2, 2);

        PostingList list1 = new PostingList(List.of(
                new Posting(1, 5, List.of(0)),
                new Posting(2, 3, List.of(1)),
                new Posting(3, 1, List.of(2)),
                new Posting(4, 7, List.of(3))
        ));
        PostingList list2 = new PostingList(List.of(
                new Posting(1, 4, List.of(0)),
                new Posting(2, 6, List.of(1)),
                new Posting(3, 2, List.of(2)),
                new Posting(4, 3, List.of(3))
        ));

        List<SearchResult> results = wand.execute(
                List.of(list1, list2),
                List.of(7.0, 6.0)
        );

        assertEquals(2, results.size());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void testEmptyPostingLists() {
        BlockMaxWAND wand = new BlockMaxWAND(10);
        List<SearchResult> results = wand.execute(List.of(), List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void testDefaultBlockSize() {
        BlockMaxWAND wand = new BlockMaxWAND(5);
        assertNotNull(wand);
    }
}
