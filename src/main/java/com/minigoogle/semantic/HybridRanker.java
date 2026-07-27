package com.minigoogle.semantic;

import com.minigoogle.query.result.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hybrid ranker that combines lexical (BM25) and semantic (embedding) scores.
 *
 * Per ARCHITECTURE.md Ch13:
 *   Hybrid retrieval combines:
 *   - Lexical retrieval (BM25) for keyword matching
 *   - Semantic retrieval (embeddings) for meaning matching
 *   - Final ranking blends both signals
 *
 * The blend is controlled by an alpha parameter:
 *   alpha = 1.0  → pure lexical (BM25)
 *   alpha = 0.0  → pure semantic
 *   alpha = 0.5  → equal blend
 */
public class HybridRanker {

    private final double alpha;

    public HybridRanker(double alpha) {
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Alpha must be in [0, 1]");
        }
        this.alpha = alpha;
    }

    public HybridRanker() {
        this(0.5);
    }

    /**
     * Ranks candidates by blending lexical and semantic scores.
     *
     * @param lexicalResults  Results with BM25/lexical scores.
     * @param semanticResults Results with cosine similarity scores.
     * @param topK            Number of results to return.
     * @return The top-K blended results.
     */
    public List<SearchResult> rank(List<SearchResult> lexicalResults,
                                    List<SearchResult> semanticResults,
                                    int topK) {
        // Normalize scores to [0, 1] range
        double maxLexical = normalizeMax(lexicalResults);
        double maxSemantic = normalizeMax(semanticResults);

        // Build a map of document ID → blended score
        java.util.Map<java.util.UUID, Double> blendedScores = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, SearchResult> docMap = new java.util.HashMap<>();

        // Add lexical contribution
        for (SearchResult r : lexicalResults) {
            if (r.document() != null) {
                java.util.UUID docId = r.document().id();
                double normalizedLexical = maxLexical > 0 ? r.score() / maxLexical : 0;
                blendedScores.merge(docId, alpha * normalizedLexical, Double::sum);
                docMap.put(docId, r);
            }
        }

        // Add semantic contribution
        for (SearchResult r : semanticResults) {
            if (r.document() != null) {
                java.util.UUID docId = r.document().id();
                double normalizedSemantic = maxSemantic > 0 ? r.score() / maxSemantic : 0;
                blendedScores.merge(docId, (1 - alpha) * normalizedSemantic, Double::sum);
                docMap.putIfAbsent(docId, r);
            }
        }

        // Sort by blended score and return top-K
        List<SearchResult> results = new ArrayList<>();
        blendedScores.entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<java.util.UUID, Double>>comparingDouble(
                        java.util.Map.Entry::getValue).reversed())
                .limit(topK)
                .forEach(entry -> {
                    SearchResult original = docMap.get(entry.getKey());
                    results.add(new SearchResult(original.document(), entry.getValue()));
                });

        return results;
    }

    private double normalizeMax(List<SearchResult> results) {
        return results.stream()
                .mapToDouble(SearchResult::score)
                .max()
                .orElse(1.0);
    }

    /**
     * @return The blend alpha parameter.
     */
    public double getAlpha() {
        return alpha;
    }
}
