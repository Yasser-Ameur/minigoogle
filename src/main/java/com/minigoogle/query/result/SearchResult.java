package com.minigoogle.query.result;

import com.minigoogle.indexer.model.IndexedDocument;

/** Record pairing a document with its relevance score, ordered by score ascending. */
public record SearchResult(IndexedDocument document, double score) implements Comparable<SearchResult> {
    @Override
    public int compareTo(SearchResult other) {
        return Double.compare(this.score, other.score);
    }
}
