package com.minigoogle.query.executor;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes Boolean operations (AND, OR, NOT) on posting lists using set algebra.
 * Provides intersect, union, and difference methods that operate on sorted
 * posting lists in linear time via a merge-based approach.
 */
public class BooleanExecutor {

    public PostingList intersect(PostingList list1, PostingList list2) {
        if (list1 == null || list2 == null) {
            return new PostingList();
        }

        List<Posting> result = new ArrayList<>();
        List<Posting> p1 = list1.getPostings();
        List<Posting> p2 = list2.getPostings();

        int i = 0, j = 0;
        while (i < p1.size() && j < p2.size()) {
            Posting pos1 = p1.get(i);
            Posting pos2 = p2.get(j);

            if (pos1.getDocumentId() == pos2.getDocumentId()) {
                // In a true boolean intersection, we just keep the document ID.
                // We'll arbitrarily keep pos1's frequencies/positions, though for BM25
                // we'll eventually need to combine term frequencies properly.
                result.add(pos1);
                i++;
                j++;
            } else if (pos1.getDocumentId() < pos2.getDocumentId()) {
                i++;
            } else {
                j++;
            }
        }

        return new PostingList(result);
    }

    public PostingList union(PostingList list1, PostingList list2) {
        if (list1 == null) return list2 != null ? list2 : new PostingList();
        if (list2 == null) return list1;

        List<Posting> result = new ArrayList<>();
        List<Posting> p1 = list1.getPostings();
        List<Posting> p2 = list2.getPostings();

        int i = 0, j = 0;
        while (i < p1.size() && j < p2.size()) {
            Posting pos1 = p1.get(i);
            Posting pos2 = p2.get(j);

            if (pos1.getDocumentId() == pos2.getDocumentId()) {
                result.add(pos1);
                i++;
                j++;
            } else if (pos1.getDocumentId() < pos2.getDocumentId()) {
                result.add(pos1);
                i++;
            } else {
                result.add(pos2);
                j++;
            }
        }

        while (i < p1.size()) {
            result.add(p1.get(i++));
        }

        while (j < p2.size()) {
            result.add(p2.get(j++));
        }

        return new PostingList(result);
    }

    public PostingList difference(PostingList universe, PostingList exclude) {
        if (universe == null) return new PostingList();
        if (exclude == null) return universe;

        List<Posting> result = new ArrayList<>();
        List<Posting> p1 = universe.getPostings();
        List<Posting> p2 = exclude.getPostings();

        int i = 0, j = 0;
        while (i < p1.size() && j < p2.size()) {
            Posting pos1 = p1.get(i);
            Posting pos2 = p2.get(j);

            if (pos1.getDocumentId() == pos2.getDocumentId()) {
                i++;
                j++;
            } else if (pos1.getDocumentId() < pos2.getDocumentId()) {
                result.add(pos1);
                i++;
            } else {
                j++;
            }
        }

        while (i < p1.size()) {
            result.add(p1.get(i++));
        }

        return new PostingList(result);
    }
}
