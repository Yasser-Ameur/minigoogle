package com.minigoogle.ml.features;

import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts normalized query-document features for the learning-to-rank model.
 *
 * <p>Every feature is derived from corpus data held by the extractor (bodies,
 * titles, URLs, lengths, PageRank and optionally the vector index) so that
 * features computed at serve time for a ranked document are identical to the
 * features computed at training time for a click signal on the same document.
 * All features are normalized into [0, 1].</p>
 */
public class FeatureExtractor {

    private final Map<Integer, String> docUrls;
    private final Map<Integer, String> docTitles;
    private final Map<Integer, String> docBodies;
    private final Map<Integer, Integer> docLengths;
    private final Map<Integer, Double> pageRankScores;
    private final VectorIndex vectorIndex;
    private final EmbeddingGenerator embeddingGenerator;
    private final double maxPageRank;
    private final double maxDocLength;

    public FeatureExtractor(Map<Integer, String> docUrls,
                            Map<Integer, String> docTitles,
                            Map<Integer, String> docBodies,
                            Map<Integer, Integer> docLengths,
                            Map<Integer, Double> pageRankScores,
                            VectorIndex vectorIndex,
                            EmbeddingGenerator embeddingGenerator) {
        this.docUrls = docUrls;
        this.docTitles = docTitles;
        this.docBodies = docBodies;
        this.docLengths = docLengths;
        this.pageRankScores = pageRankScores;
        this.vectorIndex = vectorIndex;
        this.embeddingGenerator = embeddingGenerator;
        this.maxPageRank = pageRankScores.values().stream()
                .mapToDouble(Double::doubleValue)
                .max().orElse(0.0);
        this.maxDocLength = docLengths.values().stream()
                .mapToInt(Integer::intValue)
                .max().orElse(1);
    }

    /**
     * Extracts features for a ranked document at the given result position.
     *
     * @param query    The search query.
     * @param doc      The ranked document (only the id/url/title are used).
     * @param position The 0-based position the document was served at.
     * @return The feature vector.
     */
    public QueryDocumentFeatures extract(String query, RankedDocument doc, int position) {
        return extract(query, doc.documentId(), position);
    }

    /**
     * Extracts features for a document id at the given result position.
     *
     * @param query    The search query.
     * @param documentId The document ID.
     * @param position The 0-based position the document was served at.
     * @return The feature vector.
     */
    public QueryDocumentFeatures extract(String query, int documentId, int position) {
        double[] values = new double[FeatureName.values().length];

        List<String> terms = tokenize(query);
        String body = docBodies.getOrDefault(documentId, "");
        String title = docTitles.getOrDefault(documentId, "");
        String url = docUrls.getOrDefault(documentId, "");

        values[FeatureName.BM25.ordinal()] = bm25(terms, body);
        values[FeatureName.PAGE_RANK.ordinal()] = pageRank(documentId);
        values[FeatureName.TITLE_MATCH.ordinal()] = matchFraction(terms, title);
        values[FeatureName.URL_MATCH.ordinal()] = matchFraction(terms, url);
        values[FeatureName.TERM_OVERLAP.ordinal()] = overlapFraction(terms, body);
        values[FeatureName.SEMANTIC_SIMILARITY.ordinal()] = semantic(query, documentId);
        values[FeatureName.DOC_LENGTH.ordinal()] = docLength(documentId);
        values[FeatureName.POSITION.ordinal()] = 1.0 / (position + 1.0);

        return new QueryDocumentFeatures(query, documentId, values);
    }

    /**
     * TF-saturated lexical score: 1 - exp(-sum of log-scaled term frequencies).
     * Saturating keeps the feature bounded in [0, 1] without a corpus maximum.
     */
    private double bm25(List<String> terms, String body) {
        if (terms.isEmpty() || body == null || body.isEmpty()) {
            return 0.0;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        double lexical = 0.0;
        for (String term : terms) {
            int count = countOccurrences(lower, term);
            if (count > 0) {
                lexical += Math.log1p(count);
            }
        }
        return 1.0 - Math.exp(-lexical);
    }

    private double pageRank(int documentId) {
        if (maxPageRank <= 0.0) {
            return 0.0;
        }
        return pageRankScores.getOrDefault(documentId, 0.0) / maxPageRank;
    }

    private double matchFraction(List<String> terms, String text) {
        if (terms.isEmpty() || text == null || text.isEmpty()) {
            return 0.0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                matched++;
            }
        }
        return (double) matched / terms.size();
    }

    private double overlapFraction(List<String> terms, String body) {
        if (terms.isEmpty() || body == null || body.isEmpty()) {
            return 0.0;
        }
        List<String> bodyTerms = new ArrayList<>(new java.util.LinkedHashSet<>(tokenize(body)));
        int present = 0;
        for (String term : terms) {
            if (bodyTerms.contains(term)) {
                present++;
            }
        }
        return (double) present / terms.size();
    }

    private double semantic(String query, int documentId) {
        if (vectorIndex == null || embeddingGenerator == null) {
            return 0.0;
        }
        Double sim = vectorIndex.similarity(documentId, embeddingGenerator.embed(query));
        if (sim == null) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, sim));
    }

    private double docLength(int documentId) {
        int length = docLengths.getOrDefault(documentId, 1);
        if (maxDocLength <= 1) {
            return 0.0;
        }
        return Math.log1p(length) / Math.log1p(maxDocLength);
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
