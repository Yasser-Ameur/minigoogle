package com.minigoogle.indexer.inverted;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A list of postings for a specific term.
 */
public class PostingList {

    private final List<Posting> postings;

    public PostingList() {
        this.postings = new ArrayList<>();
    }
    
    public PostingList(List<Posting> postings) {
        this.postings = new ArrayList<>(postings);
    }

    public void addPosting(Posting posting) {
        this.postings.add(posting);
    }

    public List<Posting> getPostings() {
        return postings;
    }

    public void sort() {
        postings.sort(Comparator.comparingInt(Posting::getDocumentId));
    }
    
    // In the future: Add skip pointers generation logic here
}
