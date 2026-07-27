package com.minigoogle.query.executor;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes phrase queries by performing positional intersection on posting lists.
 * Given two term posting lists and a required positional distance, it returns only
 * the documents where the terms appear at the specified distance apart.
 */
public class PhraseExecutor {

    public PostingList intersectPhrase(PostingList list1, PostingList list2, int distance) {
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
                // Documents match, now check positions
                List<Integer> validPositions = getValidPositions(pos1.getPositions(), pos2.getPositions(), distance);
                if (!validPositions.isEmpty()) {
                    // For the result, we keep the matched positions of the second term
                    result.add(new Posting(pos1.getDocumentId(), validPositions.size(), validPositions));
                }
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

    private List<Integer> getValidPositions(List<Integer> pos1, List<Integer> pos2, int requiredDistance) {
        List<Integer> valid = new ArrayList<>();
        int i = 0, j = 0;
        
        while (i < pos1.size() && j < pos2.size()) {
            int p1 = pos1.get(i);
            int p2 = pos2.get(j);
            
            if (p2 - p1 == requiredDistance) {
                valid.add(p2);
                i++;
                j++;
            } else if (p2 - p1 > requiredDistance) {
                i++;
            } else {
                j++;
            }
        }
        
        return valid;
    }
}
