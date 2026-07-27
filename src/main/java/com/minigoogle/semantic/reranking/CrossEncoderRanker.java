package com.minigoogle.semantic.reranking;

import com.minigoogle.core.retrieval.ResultReRanker;
import com.minigoogle.core.retrieval.RetrievalResult;
import com.minigoogle.ranking.model.RankedDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A cross-encoder reranker that scores query-document pairs using a heuristic model.
 *
 * <p>Scoring is based on:
 * <ul>
 *   <li>Term overlap ratio: fraction of query terms found in the document.</li>
 *   <li>Title match bonus: extra weight when query terms appear in the title.</li>
 * </ul>
 * This is a lightweight approximation of a true neural cross-encoder.</p>
 */
public class CrossEncoderRanker implements ResultReRanker {

    private static final double TITLE_BONUS = 0.3;

    /**
     * Scores a query-document pair.
     *
     * @param query    The search query.
     * @param document The document text.
     * @return A relevance score in [0.0, ~1.3] (above 1.0 possible due to title bonus).
     */
    public double score(String query, String document) {
        if (query == null || document == null) return 0.0;

        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return 0.0;

        Set<String> docTerms = tokenize(document);

        int overlap = 0;
        for (String term : queryTerms) {
            if (docTerms.contains(term)) {
                overlap++;
            }
        }
        double overlapRatio = (double) overlap / queryTerms.size();
        return overlapRatio;
    }

    /**
     * Scores a query-document pair with title awareness.
     *
     * @param query    The search query.
     * @param title    The document title.
     * @param body     The document body text.
     * @return A relevance score with title match bonus applied.
     */
    public double scoreWith(String query, String title, String body) {
        double bodyScore = score(query, body);
        double titleScore = score(query, title);
        return bodyScore + TITLE_BONUS * titleScore;
    }

    /**
     * Reranks a list of candidate documents by cross-encoder score.
     *
     * @param query      The search query.
     * @param candidates The pre-filtered candidate documents.
     * @return A new list of candidates sorted by descending cross-encoder score.
     */
    public List<RankedDocument> rerank(String query, List<RankedDocument> candidates) {
        List<RankedDocument> reranked = new ArrayList<>();

        for (RankedDocument doc : candidates) {
            double score = scoreWith(query, doc.title(), doc.snippet());
            reranked.add(new RankedDocument(
                    doc.documentId(),
                    doc.url(),
                    doc.title(),
                    doc.bm25Score(),
                    doc.pageRankScore(),
                    score,
                    doc.snippet()
            ));
        }

        reranked.sort(Comparator.comparingDouble(RankedDocument::finalScore).reversed());
        return reranked;
    }

    @Override
    public List<RetrievalResult> rerankResults(String query, List<RetrievalResult> candidates) {
        List<RankedDocument> ranked = candidates.stream()
            .map(r -> new RankedDocument(r.documentId(), r.url(), "", r.score(), 0, 0, ""))
            .collect(Collectors.toList());
        List<RankedDocument> reranked = rerank(query, ranked);
        return reranked.stream()
            .map(r -> new RetrievalResult(r.documentId(), r.finalScore(), r.url(), r.title()))
            .collect(Collectors.toList());
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new TreeSet<>();
        StringTokenizer st = new StringTokenizer(text.toLowerCase(Locale.ROOT),
                " \t\n\r\f.,;:!?'\"()[]{}-/\\");
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
