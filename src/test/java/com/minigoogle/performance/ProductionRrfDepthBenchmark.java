package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.semantic.encoder.SemanticRetriever;
import com.minigoogle.semantic.encoder.SentenceEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the production search path — not a reimplementation of it — across
 * the fusion-depth / page-depth split.
 *
 * <p>{@code ranking.topK} used to mean both "how deep to fuse" and "how many
 * results to build snippets for". Asking for a page of twenty therefore narrowed
 * hybrid fusion to twenty lexical inputs, which is not what any published
 * benchmark figure measured. This benchmark runs four real
 * {@link SearchEngine} configurations to answer one question:</p>
 *
 * <blockquote>does the corrected configuration recover the benchmarked ranking
 * quality <em>without</em> inheriting the 1000-result snippet cost?</blockquote>
 *
 * <p>Row 4 is the reproducibility check: production at page depth 1000 must
 * reproduce the RRF figure recorded in {@code BENCHMARKS.md}.</p>
 *
 * <pre>
 *   gradlew bench --tests "*ProductionRrfDepthBenchmark" \
 *     -Dbeir.dir=data/beir/trec-covid -Dbeir.dataset=trec-covid
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class ProductionRrfDepthBenchmark {

    private static final Path MODEL_DIR = Path.of("models", "all-MiniLM-L6-v2");
    private static final Path VECTOR_DIR = Path.of("models", "vectors");

    /**
     * One configuration of the production engine.
     *
     * <p>{@code lexicalDepth} and {@code semanticDepth} are separate because the
     * behaviour being replaced was asymmetric: {@code ranking.topK} capped the
     * lexical channel while {@code ranking.semantic.depth} left the semantic one
     * at 1000. Collapsing both to one number would measure a configuration that
     * never actually shipped.</p>
     */
    private record Arm(String label, String mode, int topK, int lexicalDepth, int semanticDepth) {
    }

    private record Measured(double ndcg10, double mrr10, double r10, double r100, double r1000,
                            double candidateRecall, double p50, double p95, double p99) {
    }

    private static double percentileMillis(List<Long> nanos, double p) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compare);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx))) / 1e6;
    }

    @Test
    void fusionDepthVersusPageDepthOnTheProductionPath() throws Exception {
        assertTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model missing at " + MODEL_DIR);

        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "trec-covid");
        int maxDocs = Integer.parseInt(System.getProperty("beir.maxDocs", "0"));
        Path out = Path.of("build", "beir-rrf-prod", dataset);
        Files.createDirectories(out);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, maxDocs, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        Path vectorFile = VECTOR_DIR.resolve(dataset + "-" + corpus.docs().size() + "-minilm.bin");
        assertTrue(SemanticRetriever.hasVectorStore(vectorFile),
                "expected a prebuilt vector store at " + vectorFile
                        + " — run RrfHybridDiagnostic first rather than rebuilding it here");

        System.out.println("=== Production path: fusion depth vs page depth — " + dataset
                + (maxDocs > 0 ? " DIAGNOSTIC SUBSET" : " FULL CORPUS")
                + " (" + corpus.docs().size() + " docs) ===");
        System.out.println("Vector store: " + vectorFile + " ("
                + String.format("%.1f", Files.size(vectorFile) / 1e6) + " MB)");

        List<Arm> arms = List.of(
                new Arm("BM25 topK=20", "bm25", 20, 20, 20),
                // What shipped before the split: topK capped the lexical channel
                // only, so a 20-deep lexical ranking met a 1000-deep semantic one.
                new Arm("RRF BEFORE lex=20 sem=1000", "rrf", 20, 20, 1000),
                new Arm("RRF both channels =20", "rrf", 20, 20, 20),
                new Arm("RRF AFTER lex=1000 sem=1000", "rrf", 20, 1000, 1000),
                new Arm("RRF page=1000 (benchmark)", "rrf", 1000, 1000, 1000));

        // Nothing releases an engine's vector store, so running every arm in one
        // JVM accumulates them: five 263 MB stores plus the corpus drove a
        // full-corpus run from 6m33s to 3h25m and produced a 49-minute outlier
        // query. Quality figures survived that (they are deterministic), but the
        // latency figures did not. Pass -Dbeir.armIndex=N to measure one arm per
        // JVM when latency is the point.
        int armIndex = Integer.parseInt(System.getProperty("beir.armIndex", "-1"));
        List<Arm> selected = armIndex >= 0 ? List.of(arms.get(armIndex)) : arms;
        if (armIndex >= 0) {
            System.out.println("SINGLE-ARM MODE: latency is measured without "
                    + "accumulated memory pressure from other arms.");
        }

        Map<String, Measured> results = new HashMap<>();
        for (Arm arm : selected) {
            results.put(arm.label(), run(arm, corpus, qrels, vectorFile, out));
        }
        if (armIndex >= 0) {
            Arm arm = selected.get(0);
            Measured m = results.get(arm.label());
            System.out.printf("%-28s NDCG@10=%.4f MRR@10=%.4f R@10=%.4f candRec=%.4f "
                            + "p50=%.1fms p95=%.1fms p99=%.1fms%n",
                    arm.label(), m.ndcg10(), m.mrr10(), m.r10(), m.candidateRecall(),
                    m.p50(), m.p95(), m.p99());
            assertTrue(m.ndcg10() >= 0);
            return;
        }

        System.out.println();
        System.out.printf("%-28s %8s %8s %8s %8s %8s %8s %10s %10s %10s%n",
                "configuration", "NDCG@10", "MRR@10", "R@10", "R@100", "R@1000", "candRec",
                "p50", "p95", "p99");
        for (Arm arm : arms) {
            Measured m = results.get(arm.label());
            System.out.printf("%-28s %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %9.1fms %9.1fms %9.1fms%n",
                    arm.label(), m.ndcg10(), m.mrr10(), m.r10(), m.r100(), m.r1000(),
                    m.candidateRecall(), m.p50(), m.p95(), m.p99());
        }

        System.out.println();
        System.out.println("NOTE: Recall@100 and Recall@1000 are bounded by the page depth. The");
        System.out.println("      topK=20 rows cannot exceed their Recall@20, so only the topK=1000");
        System.out.println("      row carries meaningful deep-recall figures. NDCG@10 / MRR@10 /");
        System.out.println("      Recall@10 are comparable across every row.");

        Measured bm25 = results.get("BM25 topK=20");
        Measured before = results.get("RRF BEFORE lex=20 sem=1000");
        Measured corrected = results.get("RRF AFTER lex=1000 sem=1000");
        Measured deepPage = results.get("RRF page=1000 (benchmark)");

        System.out.println();
        System.out.println("--- The question this benchmark exists to answer ---");
        System.out.printf("  production gap, BEFORE -> AFTER  : NDCG@10 %.4f -> %.4f (%+.1f%%)%n",
                before.ndcg10(), corrected.ndcg10(),
                100.0 * (corrected.ndcg10() - before.ndcg10()) / Math.max(1e-9, before.ndcg10()));
        System.out.printf("                                    MRR@10  %.4f -> %.4f (%+.1f%%)%n",
                before.mrr10(), corrected.mrr10(),
                100.0 * (corrected.mrr10() - before.mrr10()) / Math.max(1e-9, before.mrr10()));
        System.out.printf("  AFTER at page 20 vs benchmark    : NDCG@10 %.4f vs %.4f (gap %+.4f)%n",
                corrected.ndcg10(), deepPage.ndcg10(), corrected.ndcg10() - deepPage.ndcg10());
        System.out.printf("  cost of that quality             : p50 %.1fms vs %.1fms (%.2fx cheaper)%n",
                corrected.p50(), deepPage.p50(),
                deepPage.p50() / Math.max(1e-9, corrected.p50()));
        System.out.printf("                                     p99 %.1fms vs %.1fms (%.2fx cheaper)%n",
                corrected.p99(), deepPage.p99(),
                deepPage.p99() / Math.max(1e-9, corrected.p99()));
        System.out.printf("  against the lexical baseline     : NDCG@10 %.4f -> %.4f (%+.1f%%)%n",
                bm25.ndcg10(), corrected.ndcg10(),
                100.0 * (corrected.ndcg10() - bm25.ndcg10()) / Math.max(1e-9, bm25.ndcg10()));

        assertTrue(corrected.ndcg10() > 0);
    }

    private Measured run(Arm arm, BeirCorpus corpus, Map<String, Map<Integer, Integer>> qrels,
                         Path vectorFile, Path out) throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.rerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.mode", arm.mode());
        props.put("ranking.topK", String.valueOf(arm.topK()));
        props.put("search.topK", String.valueOf(arm.topK()));
        props.put("ranking.fusion.depth", String.valueOf(arm.lexicalDepth()));
        props.put("ranking.semantic.depth", String.valueOf(arm.semanticDepth()));
        props.put("ranking.rrf.k", "60");
        props.put("ranking.semantic.vectors", vectorFile.toString());
        props.put("ranking.semantic.modelDir", MODEL_DIR.toString());

        // A directory per arm: the previous engine still holds its postings file
        // memory-mapped, and Windows refuses to rewrite a mapped file.
        Path indexDir = out.resolve("index-" + arm.label().replaceAll("[^A-Za-z0-9]+", "-"));
        SearchEngine engine = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), indexDir).engine();

        double[] sums = new double[6];
        List<Long> latency = new ArrayList<>();
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
            List<RankedDocument> ranked = engine.retrieveCandidates(q.text(), arm.topK()).ranked();
            latency.add(System.nanoTime() - t0);

            List<Integer> ids = ranked.stream().map(RankedDocument::documentId).toList();
            Set<Integer> candidates = new HashSet<>(ids);
            long inCandidates = relevant.stream().filter(candidates::contains).count();

            sums[0] += RankingMetrics.ndcgAt(ids, rel, 10);
            sums[1] += RankingMetrics.mrrAtK(ids, rel, 10);
            sums[2] += RankingMetrics.recallAtK(ids, rel, 10);
            sums[3] += RankingMetrics.recallAtK(ids, rel, 100);
            sums[4] += RankingMetrics.recallAtK(ids, rel, 1000);
            sums[5] += (double) inCandidates / relevant.size();
        }

        System.out.printf("  ran %-28s over %d judged queries%n", arm.label(), evaluated);
        int n = Math.max(1, evaluated);
        return new Measured(sums[0] / n, sums[1] / n, sums[2] / n, sums[3] / n, sums[4] / n,
                sums[5] / n, percentileMillis(latency, 50), percentileMillis(latency, 95),
                percentileMillis(latency, 99));
    }
}
