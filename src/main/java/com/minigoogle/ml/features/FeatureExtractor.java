package com.minigoogle.ml.features;

import com.minigoogle.ml.click.ClickFeatureProvider;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts query-document features for the learning-to-rank model.
 *
 * <p>Feature extraction is split into two stages so the same code serves
 * standalone and distributed execution:</p>
 *
 * <ol>
 *   <li>{@link #extractRaw} computes document-local raw features (a pure
 *       function of the query and the document, independent of corpus-global
 *       statistics). A shard can compute these for the documents it owns and
 *       ship them to the coordinator.</li>
 *   <li>{@link FeatureNormalizer} applies corpus-global normalization against a
 *       {@link NormalizationContext} and assigns the rank {@code POSITION}
 *       feature. Standalone normalizes against the full local corpus; the
 *       coordinator normalizes against the maximum over shard statistics.</li>
 * </ol>
 *
 * <p>Features computed at serve time for a ranked document are therefore
 * identical to the features computed at training time for a click signal on
 * the same document: both go through {@code extractRaw} + the same
 * {@link FeatureNormalizer}.</p>
 */
public class FeatureExtractor implements ClickFeatureProvider {

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
     * @return The normalized feature vector.
     */
    public QueryDocumentFeatures extract(String query, RankedDocument doc, int position) {
        return extract(query, doc.documentId(), position);
    }

    /**
     * Extracts features for a document id at the given result position.
     *
     * @param query      The search query.
     * @param documentId The document ID.
     * @param position   The 0-based position the document was served at.
     * @return The normalized feature vector, normalized against this node's
     *         corpus ({@link #normalizationContext()}).
     */
    public QueryDocumentFeatures extract(String query, int documentId, int position) {
        return FeatureNormalizer.normalize(
                extractRaw(query, documentId), normalizationContext(), position);
    }

    /**
     * Extracts the raw (pre-normalization) features for a document id.
     *
     * <p>Every value is a pure function of the query and the document, with no
     * dependence on corpus-global statistics, so this can be computed on the
     * shard that owns the document and normalized elsewhere.</p>
     *
     * @param query      The search query.
     * @param documentId The document ID.
     * @return The raw feature vector (position is 0).
     */
    public RawFeatures extractRaw(String query, int documentId) {
        List<String> terms = tokenize(query);
        String body = docBodies.getOrDefault(documentId, "");
        String title = docTitles.getOrDefault(documentId, "");
        String url = docUrls.getOrDefault(documentId, "");
        // Lowercase the body once. Both the lexical score and the overlap
        // fraction need the folded form, and the body is by far the largest
        // string here, so folding it twice per document doubled the dominant
        // allocation in this method.
        String lowerBody = body.isEmpty() ? "" : body.toLowerCase(Locale.ROOT);
        return new RawFeatures(
                bm25(terms, lowerBody),
                pageRankScores.getOrDefault(documentId, 0.0),
                matchFraction(terms, title),
                matchFraction(terms, url),
                overlapFraction(terms, lowerBody),
                semantic(query, documentId),
                docLengths.getOrDefault(documentId, 1),
                0.0);
    }

    /**
     * @return This node's corpus-global normalization context. Standalone mode
     *         ranks against this context; a coordinator ranks against the
     *         maximum over the shard contexts.
     */
    public NormalizationContext normalizationContext() {
        return new NormalizationContext(maxPageRank, maxDocLength);
    }

    @Override
    public QueryDocumentFeatures features(String query, int documentId) {
        return extract(query, documentId, 0);
    }

    /**
     * TF-saturated lexical score: 1 - exp(-sum of log-scaled term frequencies).
     * Saturating keeps the feature bounded in [0, 1] without a corpus maximum.
     */
    private double bm25(List<String> terms, String lowerBody) {
        if (terms.isEmpty() || lowerBody == null || lowerBody.isEmpty()) {
            return 0.0;
        }
        String lower = lowerBody;
        double lexical = 0.0;
        for (String term : terms) {
            int count = countOccurrences(lower, term);
            if (count > 0) {
                lexical += Math.log1p(count);
            }
        }
        return 1.0 - Math.exp(-lexical);
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

    private double overlapFraction(List<String> terms, String lowerBody) {
        if (terms.isEmpty() || lowerBody == null || lowerBody.isEmpty()) {
            return 0.0;
        }
        // Look the terms up in the set directly. The previous version built a
        // LinkedHashSet to deduplicate and then copied it into an ArrayList,
        // which turned every lookup into an O(n) scan of the document's whole
        // vocabulary for no benefit — the order was never used.
        Set<String> bodyTerms = tokenizeToSet(lowerBody);
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

    /**
     * Precompiled so tokenization does not recompile the pattern on every call.
     * {@code String.split} only avoids compilation for single-character
     * patterns, which this is not, and tokenization runs once per served
     * document.
     */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (String token : NON_ALPHANUMERIC.split(text.toLowerCase(Locale.ROOT))) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Tokenizes text that is <em>already</em> lowercased into a set, for
     * membership tests. Avoids the intermediate list and the second fold.
     */
    private static Set<String> tokenizeToSet(String lowerText) {
        Set<String> tokens = new HashSet<>();
        if (lowerText == null || lowerText.isBlank()) {
            return tokens;
        }
        for (String token : NON_ALPHANUMERIC.split(lowerText)) {
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
