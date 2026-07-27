package com.minigoogle.indexer.inverted;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory representation of an inverted index.
 */
public class InvertedIndex {

    private final Map<String, PostingList> index;

    public InvertedIndex() {
        this.index = new HashMap<>();
    }

    public void addPosting(String term, Posting posting) {
        index.computeIfAbsent(term, k -> new PostingList()).addPosting(posting);
    }

    public Map<String, PostingList> getIndex() {
        return index;
    }

    public void sortAllPostingLists() {
        for (PostingList postingList : index.values()) {
            postingList.sort();
        }
    }
}
