package com.minigoogle.indexer.inverted;

import java.util.List;

/**
 * Represents a single posting (document ID, frequency, and positions) for a term.
 */
public class Posting {
    private final int documentId;
    private final int frequency;
    private final List<Integer> positions;

    public Posting(int documentId, int frequency, List<Integer> positions) {
        this.documentId = documentId;
        this.frequency = frequency;
        this.positions = positions;
    }

    public int getDocumentId() {
        return documentId;
    }

    public int getFrequency() {
        return frequency;
    }

    public List<Integer> getPositions() {
        return positions;
    }
}
