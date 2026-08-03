package com.minigoogle.semantic.rag;

import com.minigoogle.core.retrieval.RetrievalEngine;
import com.minigoogle.core.retrieval.RetrievalResult;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * End-to-end retrieval pipeline combining lexical and semantic search.
 *
 * <p>This pipeline performs hybrid retrieval by:
 * <ol>
 *   <li>Computing a BM25-style lexical score using term frequency weighting.</li>
 *   <li>Computing a semantic score via the vector index.</li>
 *   <li>Merging and deduplicating results with blended scores.</li>
 * </ol>
 *
 * <p>The lexical scorer is injected via constructor to allow flexible integration
 * with the existing inverted index infrastructure.</p>
 */
public class RetrievalPipeline implements RetrievalEngine {

    private final EmbeddingGenerator embeddingGenerator;
    private final VectorIndex vectorIndex;
    private final BiFunction<String, Integer, List<VectorIndex.VectorResult>> lexicalSearchFn;
    private final double lexicalWeight;

    /**
     * Creates a retrieval pipeline with equal weight for lexical and semantic search.
     *
     * @param embeddingGenerator The embedding generator for query vectors.
     * @param vectorIndex        The vector index for semantic search.
     * @param lexicalSearchFn    A function that performs lexical search and returns results.
     */
    public RetrievalPipeline(EmbeddingGenerator embeddingGenerator,
                             VectorIndex vectorIndex,
                             BiFunction<String, Integer, List<VectorIndex.VectorResult>> lexicalSearchFn) {
        this(embeddingGenerator, vectorIndex, lexicalSearchFn, 0.5);
    }

    /**
     * Creates a retrieval pipeline with configurable lexical/semantic weight.
     *
     * @param embeddingGenerator The embedding generator for query vectors.
     * @param vectorIndex        The vector index for semantic search.
     * @param lexicalSearchFn    A function that performs lexical search and returns results.
     * @param lexicalWeight      Weight for lexical results in [0.0, 1.0]. Semantic weight = 1 - lexicalWeight.
     */
    public RetrievalPipeline(EmbeddingGenerator embeddingGenerator,
                             VectorIndex vectorIndex,
                             BiFunction<String, Integer, List<VectorIndex.VectorResult>> lexicalSearchFn,
                             double lexicalWeight) {
        this.embeddingGenerator = embeddingGenerator;
        this.vectorIndex = vectorIndex;
        this.lexicalSearchFn = lexicalSearchFn;
        this.lexicalWeight = lexicalWeight;
    }

    /**
     * Performs hybrid retrieval, combining lexical and semantic results.
     *
     * @param query The search query.
     * @param topK  Number of results to return.
     * @return A merged, deduplicated list of results sorted by blended score.
     */
    public List<VectorIndex.VectorResult> retrieveRaw(String query, int topK) {
        int fetchK = topK * 3;

        List<VectorIndex.VectorResult> lexicalResults = lexicalSearchFn.apply(query, fetchK);
        double[] queryVector = embeddingGenerator.embed(query);
        List<VectorIndex.VectorResult> semanticResults = vectorIndex.search(queryVector, fetchK);

        return mergeResults(lexicalResults, semanticResults, topK, lexicalWeight);
    }

    /**
     * Merges lexical and semantic retrieval results into a single deduplicated,
     * blended-score list.
     *
     * <p>Each result list is normalized by its own maximum score, then combined
     * as {@code lexicalWeight * normLexical + (1 - lexicalWeight) * normSemantic}.
     * Documents that appear in both lists accumulate both contributions. Ties are
     * broken by document id for determinism.</p>
     *
     * @param lexical       Lexical search results (may be empty).
     * @param semantic      Semantic search results (may be empty).
     * @param topK          Maximum number of merged results to return.
     * @param lexicalWeight Weight of lexical scores in [0.0, 1.0].
     * @return The merged, ranked result list.
     */
    public static List<VectorIndex.VectorResult> mergeResults(
            List<VectorIndex.VectorResult> lexical,
            List<VectorIndex.VectorResult> semantic,
            int topK,
            double lexicalWeight) {
        double maxLexical = normalizeMax(lexical);
        double maxSemantic = normalizeMax(semantic);

        Map<Integer, Double> scores = new LinkedHashMap<>();
        Map<Integer, VectorIndex.VectorResult> resultMap = new LinkedHashMap<>();

        for (VectorIndex.VectorResult r : lexical) {
            double normalized = maxLexical > 0 ? r.score() / maxLexical : 0;
            scores.merge(r.id(), lexicalWeight * normalized, Double::sum);
            resultMap.putIfAbsent(r.id(), r);
        }

        for (VectorIndex.VectorResult r : semantic) {
            double normalized = maxSemantic > 0 ? r.score() / maxSemantic : 0;
            scores.merge(r.id(), (1 - lexicalWeight) * normalized, Double::sum);
            resultMap.putIfAbsent(r.id(), r);
        }

        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Comparator
                .<Map.Entry<Integer, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        List<VectorIndex.VectorResult> results = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : sorted) {
            if (results.size() >= topK) break;
            VectorIndex.VectorResult original = resultMap.get(entry.getKey());
            results.add(new VectorIndex.VectorResult(original.id(), entry.getValue(), original.metadata()));
        }

        return results;
    }

    @Override
    public List<RetrievalResult> retrieve(String query, int topK) {
        List<VectorIndex.VectorResult> raw = retrieveRaw(query, topK);
        return raw.stream()
            .map(r -> new RetrievalResult(r.id(), r.score(), "", ""))
            .collect(Collectors.toList());
    }

    private static double normalizeMax(List<VectorIndex.VectorResult> results) {
        return results.stream()
                .mapToDouble(VectorIndex.VectorResult::score)
                .max()
                .orElse(1.0);
    }
}
