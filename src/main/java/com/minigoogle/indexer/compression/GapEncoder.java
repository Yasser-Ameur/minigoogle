package com.minigoogle.indexer.compression;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes posting list document IDs using gap encoding for compression,
 * storing the difference between consecutive IDs instead of absolute values.
 */
public class GapEncoder {
    public List<Integer> encode(List<Integer> documentIds) {
        List<Integer> gaps = new ArrayList<>(documentIds.size());
        if (documentIds.isEmpty()) return gaps;

        gaps.add(documentIds.get(0));
        for (int i = 1; i < documentIds.size(); i++) {
            gaps.add(documentIds.get(i) - documentIds.get(i - 1));
        }
        return gaps;
    }

    public List<Integer> decode(List<Integer> gaps) {
        List<Integer> documentIds = new ArrayList<>(gaps.size());
        if (gaps.isEmpty()) return documentIds;

        int current = gaps.get(0);
        documentIds.add(current);
        
        for (int i = 1; i < gaps.size(); i++) {
            current += gaps.get(i);
            documentIds.add(current);
        }
        return documentIds;
    }
}
