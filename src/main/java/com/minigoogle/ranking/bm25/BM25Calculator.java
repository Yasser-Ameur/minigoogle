package com.minigoogle.ranking.bm25;

import java.util.List;
import java.util.Map;

/**
 * Implements the Okapi BM25 ranking function.
 *
 * BM25 improves upon TF-IDF by incorporating document length normalization
 * and diminishing returns for term frequency saturation.
 *
 * Per-term score:
 *   IDF(t) × [ TF(t,d) × (k1 + 1) ] / [ TF(t,d) + k1 × (1 - b + b × |d| / avgdl) ]
 *
 * where IDF uses the smoothed variant:
 *   IDF(t) = ln( (N - df(t) + 0.5) / (df(t) + 0.5) + 1 )
 */
public class BM25Calculator {

    private final BM25Parameters params;

    public BM25Calculator(BM25Parameters params) {
        this.params = params;
    }

    /**
     * Computes the smoothed IDF for a term given its document frequency.
     *
     * @param df Number of documents containing the term.
     * @return The IDF value.
     */
    public double idf(int df) {
        long N = params.totalDocuments();
        return Math.log(((N - df + 0.5) / (df + 0.5)) + 1.0);
    }

    /**
     * Scores a single term within a single document.
     *
     * @param tf        Term frequency within the document.
     * @param docLength Length (word count) of the document.
     * @param df        Document frequency of the term across the corpus.
     * @return BM25 score contribution for this term in this document.
     */
    public double scoreTermInDocument(int tf, int docLength, int df) {
        double idfVal = idf(df);
        double k1 = params.k1();
        double b = params.b();
        double avgdl = params.averageDocLength();

        double numerator = tf * (k1 + 1.0);
        double denominator = tf + k1 * (1.0 - b + b * (docLength / avgdl));

        return idfVal * (numerator / denominator);
    }

    /**
     * Computes the total BM25 score for a document across all query terms.
     * The total score is the sum of individual term scores.
     *
     * @param queryTerms         The list of stemmed query terms.
     * @param termFrequencies    Map of term → term frequency within this document.
     * @param docLength          Length of the document in words.
     * @param documentFrequencies Map of term → document frequency across the corpus.
     * @return The total BM25 score for the document.
     */
    public double scoreDocument(List<String> queryTerms,
                                Map<String, Integer> termFrequencies,
                                int docLength,
                                Map<String, Integer> documentFrequencies) {
        double totalScore = 0.0;

        for (String term : queryTerms) {
            int tf = termFrequencies.getOrDefault(term, 0);
            int df = documentFrequencies.getOrDefault(term, 0);

            if (tf > 0 && df > 0) {
                totalScore += scoreTermInDocument(tf, docLength, df);
            }
        }

        return totalScore;
    }
}
