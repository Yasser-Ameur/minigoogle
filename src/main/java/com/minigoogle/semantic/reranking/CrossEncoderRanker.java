package com.minigoogle.semantic.reranking;

import com.minigoogle.core.retrieval.ResultReRanker;
import com.minigoogle.core.retrieval.RetrievalResult;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A cross-encoder reranker that scores query-document pairs.
 *
 * <p>When a {@link VectorIndex} of document embeddings is supplied, scoring uses
 * real content-based cosine similarity between the query embedding and each
 * document embedding, blended with the lexical {@code finalScore} from the
 * ranking pipeline. This replaces the previous simulated/heuristic behavior.</p>
 *
 * <p>Without a vector index (fallback path) it scores using a lightweight
 * term-overlap approximation.</p>
 */
public class CrossEncoderRanker implements ResultReRanker {

    private static final double TITLE_BONUS = 0.3;
    private static final double DEFAULT_SEMANTIC_WEIGHT = 0.3;

    private final VectorIndex vectorIndex;
    private final EmbeddingGenerator embeddingGenerator;
    private final double semanticWeight;

    public CrossEncoderRanker() {
        this(null, null, DEFAULT_SEMANTIC_WEIGHT);
    }

    public CrossEncoderRanker(VectorIndex vectorIndex) {
        this(vectorIndex, null, DEFAULT_SEMANTIC_WEIGHT);
    }

    public CrossEncoderRanker(VectorIndex vectorIndex, double semanticWeight) {
        this(vectorIndex, null, semanticWeight);
    }

    public CrossEncoderRanker(VectorIndex vectorIndex, EmbeddingGenerator embeddingGenerator, double semanticWeight) {
        if (semanticWeight < 0 || semanticWeight > 1) {
            throw new IllegalArgumentException("Semantic weight must be in [0, 1]");
        }
        this.vectorIndex = vectorIndex;
        this.embeddingGenerator = embeddingGenerator;
        this.semanticWeight = semanticWeight;
    }

    /**
     * Scores a query-document pair using term overlap.
     *
     * @param query    The search query.
     * @param document The document text.
     * @return A relevance score in [0.0, 1.0].
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
        return (double) overlap / queryTerms.size();
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
     * Reranks a list of candidate documents by semantic relevance.
     *
     * <p>When a vector index is available the query is embedded and each
     * candidate's cosine similarity to the query is blended with its lexical
     * {@code finalScore}: {@code (1 - semanticWeight) * normalizedLexical +
     * semanticWeight * cosine}. Otherwise term-overlap scoring is used.</p>
     *
     * @param query      The search query.
     * @param candidates The pre-filtered candidate documents.
     * @return A new list of candidates sorted by descending combined score.
     */
    public List<RankedDocument> rerank(String query, List<RankedDocument> candidates) {
        if (vectorIndex == null) {
            return rerankByTermOverlap(query, candidates);
        }

        double[] queryVector = embeddingFor(query);
        double maxLexical = candidates.stream()
                .mapToDouble(RankedDocument::finalScore)
                .max()
                .orElse(0.0);

        List<RankedDocument> reranked = new ArrayList<>();
        for (RankedDocument doc : candidates) {
            Double cosine = vectorIndex.similarity(doc.documentId(), queryVector);
            double semantic = cosine != null ? Math.max(0.0, cosine) : 0.0;
            double normalizedLexical = maxLexical > 0 ? doc.finalScore() / maxLexical : 0.0;
            double combined = (1 - semanticWeight) * normalizedLexical + semanticWeight * semantic;
            reranked.add(new RankedDocument(
                    doc.documentId(),
                    doc.url(),
                    doc.title(),
                    doc.bm25Score(),
                    doc.pageRankScore(),
                    combined,
                    doc.snippet()
            ));
        }

        reranked.sort(Comparator.comparingDouble(RankedDocument::finalScore).reversed());
        return reranked;
    }

    private List<RankedDocument> rerankByTermOverlap(String query, List<RankedDocument> candidates) {
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

    private double[] embeddingFor(String text) {
        if (embeddingGenerator != null) {
            return embeddingGenerator.embed(text);
        }
        return new EmbeddingGenerator(vectorIndex.getDimension()).embed(text);
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
