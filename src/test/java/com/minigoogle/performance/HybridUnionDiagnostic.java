package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.semantic.encoder.SemanticRetriever;
import com.minigoogle.semantic.encoder.SentenceEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The central experiment: BM25 alone, semantic alone, and their candidate union
 * evaluated with the <em>unchanged</em> ranker.
 *
 * <h2>How the union is formed</h2>
 * BM25's ranked output first, in its own order, then semantic candidates it did
 * not already contain, in semantic-similarity order. That is a candidate-set
 * union with a documented ordering — <b>not</b> score fusion: no BM25 score is
 * ever combined with a similarity, and BM25's relative ordering is untouched.
 *
 * <p>This is the honest operationalisation of "union, then existing ranking",
 * because the existing ranker <em>cannot</em> rank a semantic-only document:
 * {@code RankingPipeline.rank} builds its candidate map purely from query-term
 * posting lists, so a document containing none of the query terms never enters
 * scoring, and {@code BM25Calculator} guards {@code tf > 0} so its score would be
 * exactly zero anyway. Appending is therefore the most favourable placement the
 * unchanged ranker can give such a document.</p>
 *
 * <p>Which means this experiment has a predictable ceiling, and measuring where
 * it lands is the point: any gain can only appear at depths BM25 did not already
 * fill.</p>
 *
 * <pre>
 *   gradlew bench --tests "*HybridUnionDiagnostic" \
 *     -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class HybridUnionDiagnostic {

    private static final Path MODEL_DIR = Path.of("models", "all-MiniLM-L6-v2");

    private record Metrics(double ndcg10, double mrr10, double r10, double r100, double r1000,
                           double candidateRecall, double meanCandidates, double p50Millis) {
    }

    private static Metrics average(List<double[]> rows, List<Long> latencies) {
        double[] sum = new double[7];
        for (double[] r : rows) {
            for (int i = 0; i < 7; i++) {
                sum[i] += r[i];
            }
        }
        int n = Math.max(1, rows.size());
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compare);
        double p50 = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        return new Metrics(sum[0] / n, sum[1] / n, sum[2] / n, sum[3] / n,
                sum[4] / n, sum[5] / n, sum[6] / n, p50);
    }

    private static void print(String label, Metrics m) {
        System.out.printf("  %-22s NDCG@10=%.4f MRR@10=%.4f R@10=%.4f R@100=%.4f R@1000=%.4f "
                        + "candRecall=%.4f cands=%.0f p50=%.0fms%n",
                label, m.ndcg10(), m.mrr10(), m.r10(), m.r100(), m.r1000(),
                m.candidateRecall(), m.meanCandidates(), m.p50Millis());
    }

    @Test
    void bm25VersusSemanticVersusUnion() throws Exception {
        assertTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model missing at " + MODEL_DIR);

        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "scifact");
        int deepK = Integer.parseInt(System.getProperty("beir.deepK", "1000"));
        int maxDocs = Integer.parseInt(System.getProperty("beir.maxDocs", "0"));
        int threads = Integer.parseInt(System.getProperty("beir.threads", "8"));
        Path out = Path.of(System.getProperty("beir.out", "build/beir-union/" + dataset));
        Files.createDirectories(out);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, maxDocs, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        // Ranking configuration is the current production default and is NOT
        // varied: the only difference between the three systems is which
        // candidates exist.
        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.rerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.topK", String.valueOf(deepK));
        props.put("search.topK", String.valueOf(deepK));

        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), out.resolve("index"));
        SearchEngine engine = build.engine();

        System.out.println("=== BM25 vs semantic vs union: " + dataset + " ("
                + corpus.docs().size() + " docs"
                + (maxDocs > 0 ? " [CAPPED at " + maxDocs + " - diagnostic subset]" : "")
                + ", deepK=" + deepK + ") ===");

        Path vectorFile = out.resolve("vectors-" + corpus.docs().size() + ".bin");
        SentenceEncoder encoder = SentenceEncoder.load(
                MODEL_DIR, SentenceEncoder.DEFAULT_MAX_TOKENS, SentenceEncoder.MINILM_DIMENSION, threads);
        try {
            if (!SemanticRetriever.hasVectorStore(vectorFile)) {
                List<String> texts = new ArrayList<>(corpus.docs().size());
                for (ParsedDocument d : corpus.docs()) {
                    texts.add(d.title() + " " + d.text());
                }
                long t0 = System.nanoTime();
                SemanticRetriever.buildVectorStore(encoder, texts, vectorFile, (done, total) -> {
                    if (done % 6400 == 0) {
                        System.out.printf("  embedded %d/%d%n", done, total);
                    }
                });
                System.out.printf("vector store built in %.1fs -> %.1f MB%n",
                        (System.nanoTime() - t0) / 1e9, Files.size(vectorFile) / 1e6);
            } else {
                System.out.println("reusing vector store " + vectorFile);
            }

            try (SemanticRetriever semantic = SemanticRetriever.load(encoder, vectorFile)) {
                int[] semanticDepths = {50, 100, 500, 1000};

                List<double[]> bm25Rows = new ArrayList<>();
                List<double[]> semRows = new ArrayList<>();
                Map<Integer, List<double[]>> unionRows = new HashMap<>();
                for (int k : semanticDepths) {
                    unionRows.put(k, new ArrayList<>());
                }
                List<Long> bm25Lat = new ArrayList<>();
                List<Long> semLat = new ArrayList<>();
                Map<Integer, List<Long>> unionLat = new HashMap<>();
                for (int k : semanticDepths) {
                    unionLat.put(k, new ArrayList<>());
                }

                // Where do semantic-only relevant documents actually end up?
                long semanticOnlyTotal = 0, inUnion = 0, top1000 = 0, top100 = 0, top10 = 0;
                int evaluated = 0;

                for (BeirQuery q : corpus.queries()) {
                    Map<Integer, Integer> rel = qrels.getOrDefault(q.id(), Map.of());
                    Set<Integer> relevant = new HashSet<>();
                    for (Map.Entry<Integer, Integer> e : rel.entrySet()) {
                        if (e.getValue() > 0) {
                            relevant.add(e.getKey());
                        }
                    }
                    if (relevant.isEmpty()) {
                        continue;
                    }
                    evaluated++;

                    long t0 = System.nanoTime();
                    List<RankedDocument> lexicalRanked = engine.retrieveCandidates(q.text(), deepK).ranked();
                    long bm25Millis = (System.nanoTime() - t0) / 1_000_000;
                    List<Integer> lexicalIds = lexicalRanked.stream()
                            .map(RankedDocument::documentId).toList();
                    Set<Integer> lexicalSet = new HashSet<>(lexicalIds);
                    bm25Lat.add(bm25Millis);
                    bm25Rows.add(row(lexicalIds, rel, relevant, deepK, lexicalSet));

                    t0 = System.nanoTime();
                    List<SemanticRetriever.Candidate> semTop =
                            semantic.retrieve(q.text(), Math.max(deepK, 1000));
                    long semMillis = (System.nanoTime() - t0) / 1_000_000;
                    List<Integer> semIds = semTop.stream()
                            .map(SemanticRetriever.Candidate::documentId).toList();
                    semLat.add(semMillis);
                    semRows.add(row(semIds, rel, relevant, deepK, new HashSet<>(semIds)));

                    for (int k : semanticDepths) {
                        List<Integer> semK = semIds.subList(0, Math.min(k, semIds.size()));
                        // Union: BM25 order preserved, then semantic-only ids appended
                        // in similarity order. No scores are combined.
                        LinkedHashSet<Integer> union = new LinkedHashSet<>(lexicalIds);
                        union.addAll(semK);
                        List<Integer> unionIds = new ArrayList<>(union);
                        unionRows.get(k).add(row(unionIds, rel, relevant, deepK, new HashSet<>(unionIds)));
                        unionLat.get(k).add(bm25Millis + semMillis);
                    }

                    // Track semantic-only relevant documents through the pipeline
                    // at the deepest semantic setting.
                    List<Integer> semDeep = semIds.subList(0, Math.min(1000, semIds.size()));
                    LinkedHashSet<Integer> unionDeep = new LinkedHashSet<>(lexicalIds);
                    unionDeep.addAll(semDeep);
                    List<Integer> unionDeepList = new ArrayList<>(unionDeep);
                    Set<Integer> semDeepSet = new HashSet<>(semDeep);

                    for (int docId : relevant) {
                        if (lexicalSet.contains(docId) || !semDeepSet.contains(docId)) {
                            continue;
                        }
                        semanticOnlyTotal++;
                        int rank = unionDeepList.indexOf(docId) + 1;
                        if (rank > 0) {
                            inUnion++;
                            if (rank <= 1000) {
                                top1000++;
                            }
                            if (rank <= 100) {
                                top100++;
                            }
                            if (rank <= 10) {
                                top10++;
                            }
                        }
                    }
                }

                System.out.println();
                System.out.println("--- Systems (identical ranking configuration) ---");
                print("A: BM25 only", average(bm25Rows, bm25Lat));
                print("B: semantic only", average(semRows, semLat));
                for (int k : semanticDepths) {
                    print("C: union (semK=" + k + ")", average(unionRows.get(k), unionLat.get(k)));
                }

                System.out.println();
                System.out.println("--- Semantic-only relevant documents through the pipeline ---");
                System.out.printf("  found by semantic, missed by BM25 : %d%n", semanticOnlyTotal);
                System.out.printf("  present in union candidate pool   : %d  (%.1f%%)%n",
                        inUnion, pct(inUnion, semanticOnlyTotal));
                System.out.printf("  reach union rank <= 1000          : %d  (%.1f%%)%n",
                        top1000, pct(top1000, semanticOnlyTotal));
                System.out.printf("  reach union rank <= 100           : %d  (%.1f%%)%n",
                        top100, pct(top100, semanticOnlyTotal));
                System.out.printf("  reach union rank <= 10            : %d  (%.1f%%)%n",
                        top10, pct(top10, semanticOnlyTotal));

                assertTrue(evaluated > 0);
            }
        } finally {
            // SemanticRetriever.close() closes the encoder it was given.
            if (!SemanticRetriever.hasVectorStore(vectorFile)) {
                encoder.close();
            }
        }
    }

    /** {ndcg10, mrr10, r10, r100, r1000, candidateRecall, candidateCount} */
    private static double[] row(List<Integer> ranked, Map<Integer, Integer> rel,
                                Set<Integer> relevant, int deepK, Set<Integer> candidates) {
        long inCandidates = relevant.stream().filter(candidates::contains).count();
        return new double[]{
                RankingMetrics.ndcgAt(ranked, rel, 10),
                RankingMetrics.mrrAtK(ranked, rel, 10),
                RankingMetrics.recallAtK(ranked, rel, 10),
                RankingMetrics.recallAtK(ranked, rel, 100),
                RankingMetrics.recallAtK(ranked, rel, Math.max(deepK, 1000)),
                (double) inCandidates / relevant.size(),
                candidates.size()
        };
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0 : 100.0 * n / total;
    }
}
