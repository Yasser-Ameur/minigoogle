package com.minigoogle.ranking.model;

/**
 * A fully ranked search result with all scoring components and a generated snippet.
 *
 * @param documentId    The internal document ID.
 * @param url           The document URL.
 * @param title         The document title.
 * @param bm25Score     Raw BM25 text relevance score.
 * @param pageRankScore Raw PageRank authority score.
 * @param finalScore    Combined weighted score after normalization and fusion.
 * @param snippet       Highlighted snippet from the document body.
 */
public record RankedDocument(
        int documentId,
        String url,
        String title,
        double bm25Score,
        double pageRankScore,
        double finalScore,
        String snippet
) implements Comparable<RankedDocument> {

    @Override
    public int compareTo(RankedDocument other) {
        // Higher scores first (descending order)
        return Double.compare(other.finalScore, this.finalScore);
    }
}
