package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ml.eval.SyntheticCorpus;
import com.minigoogle.ml.eval.SyntheticCorpus.JudgedCorpus;
import com.minigoogle.ml.eval.SyntheticCorpus.JudgedQuery;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.features.RawFeatures;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.GlobalRankingPipeline;
import com.minigoogle.ranking.pipeline.RankedCandidate;
import com.minigoogle.search.RetrievalResult;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retrieval-quality benchmark: NDCG@10 measured on the real production search
 * path against a seeded judged corpus.
 *
 * <p>Unlike the latency benchmarks in this package these numbers are fully
 * deterministic — same corpus, same judgments, same code path, same result on
 * every run — so the guards here are exact regression floors rather than loose
 * timing bounds. A ranking change that degrades NDCG@10 fails the build.</p>
 *
 * <p>NDCG@10 uses the standard TREC formulation ({@code 2^rel - 1} gain,
 * {@code 1/log2(rank+1)} discount, normalized against the ideal ranking of the
 * judged documents truncated at 10). The metric itself is unit-tested against
 * hand-computed values in {@code RankingMetricsTest}; this benchmark measures
 * what the engine actually achieves with it.</p>
 */
class RankingQualityBenchmarks {

    /** Candidate depth requested from the retrieval stage before final ranking. */
    private static final int SERVED_K = 60;

    /** Corpus seed and shape. Changing these changes every number below. */
    private static final long SEED = 42;
    private static final int TOPICS = 8;
    private static final int DOCS_PER_TOPIC = 40;

    @TempDir
    Path tempDir;

    private record QualityReport(double ndcgAt10,
                                 double map,
                                 double recallAt5,
                                 double precisionAt5,
                                 double mrr,
                                 double worstQueryNdcg,
                                 int queriesWithNoRelevantHit,
                                 int queries) {
    }

    @Test
    void ndcgAt10OnTheProductionSearchPath() throws IOException {
        JudgedCorpus corpus = SyntheticCorpus.generate(SEED, TOPICS, DOCS_PER_TOPIC);
        Map<String, Map<Integer, Integer>> relevance = relevanceByQuery(corpus);

        SearchEngine lexical = SearchEngineBuilder.build(
                corpus.docs(),
                new Configuration(Map.of(
                        "semantic.enabled", "false",
                        "semantic.hybrid.enabled", "false",
                        "semantic.expansion.enabled", "false")),
                tempDir.resolve("quality-bm25")).engine();

        SearchEngineBuild hybridBuild = SearchEngineBuilder.build(
                corpus.docs(),
                new Configuration(Map.of(
                        "semantic.enabled", "true",
                        "semantic.hybrid.enabled", "true")),
                tempDir.resolve("quality-hybrid"));
        SearchEngine hybrid = hybridBuild.engine();

        QualityReport bm25Report = evaluate(lexical, corpus, relevance, null);
        QualityReport hybridReport = evaluate(hybrid, corpus, relevance, new LinearRankingModel());

        System.out.println("=== Retrieval quality: NDCG@10 (seed=" + SEED + ", " + TOPICS
                + " topics x " + DOCS_PER_TOPIC + " docs = " + corpus.docs().size()
                + " docs, " + bm25Report.queries() + " judged queries, servedK=" + SERVED_K + ") ===");
        print("BM25 lexical only", bm25Report);
        print("Hybrid + LTR", hybridReport);
        System.out.printf("  hybrid vs BM25: NDCG@10 %+.1f%%  MAP %+.1f%%%n",
                100.0 * (hybridReport.ndcgAt10() - bm25Report.ndcgAt10()) / bm25Report.ndcgAt10(),
                100.0 * (hybridReport.map() - bm25Report.map()) / bm25Report.map());

        // ── Regression guards ──
        // Floors sit just below the measured values so a genuine ranking
        // regression fails while harmless numerical drift does not. They are not
        // targets: raise them when a change legitimately improves quality.
        assertTrue(bm25Report.ndcgAt10() > 0.60,
                "BM25 NDCG@10 regressed: " + bm25Report.ndcgAt10());
        assertTrue(hybridReport.ndcgAt10() > 0.65,
                "Hybrid NDCG@10 regressed: " + hybridReport.ndcgAt10());
        assertTrue(hybridReport.ndcgAt10() > bm25Report.ndcgAt10(),
                "Hybrid retrieval must outrank the lexical baseline ("
                        + hybridReport.ndcgAt10() + " vs " + bm25Report.ndcgAt10() + ")");

        // Every judged query must surface at least one relevant document in its
        // top 10. A non-zero count here means some queries fail outright, which
        // an averaged NDCG can hide.
        assertEquals(0, hybridReport.queriesWithNoRelevantHit(),
                "queries returning no relevant document in the top 10");

        // NDCG is a ratio and must stay in [0,1] for every variant.
        for (QualityReport r : List.of(bm25Report, hybridReport)) {
            assertTrue(r.ndcgAt10() >= 0.0 && r.ndcgAt10() <= 1.0,
                    "NDCG@10 outside [0,1]: " + r.ndcgAt10());
        }
    }

    /**
     * Quality must be reproducible: the same corpus and engine configuration have
     * to produce the identical NDCG@10 on every run, otherwise the guards above
     * are measuring noise and any before/after quality comparison is worthless.
     */
    @Test
    void ndcgAt10IsDeterministicAcrossRuns() throws IOException {
        JudgedCorpus corpus = SyntheticCorpus.generate(SEED, TOPICS, DOCS_PER_TOPIC);
        Map<String, Map<Integer, Integer>> relevance = relevanceByQuery(corpus);

        SearchEngine first = SearchEngineBuilder.build(corpus.docs(),
                new Configuration(Map.of()), tempDir.resolve("determinism-a")).engine();
        SearchEngine second = SearchEngineBuilder.build(corpus.docs(),
                new Configuration(Map.of()), tempDir.resolve("determinism-b")).engine();

        double a = evaluate(first, corpus, relevance, new LinearRankingModel()).ndcgAt10();
        double b = evaluate(second, corpus, relevance, new LinearRankingModel()).ndcgAt10();

        System.out.printf("=== NDCG@10 determinism: build A=%.6f  build B=%.6f%n", a, b);
        assertEquals(a, b, 1e-12,
                "two identical index builds must yield identical NDCG@10");
    }

    private static void print(String label, QualityReport r) {
        System.out.printf("  %-22s NDCG@10=%.4f MAP=%.4f Recall@5=%.4f Precision@5=%.4f "
                        + "MRR=%.4f worstQueryNDCG=%.4f%n",
                label, r.ndcgAt10(), r.map(), r.recallAt5(), r.precisionAt5(),
                r.mrr(), r.worstQueryNdcg());
    }

    private static QualityReport evaluate(SearchEngine engine,
                                          JudgedCorpus corpus,
                                          Map<String, Map<Integer, Integer>> relevanceByQuery,
                                          LinearRankingModel model) {
        double ndcg = 0, map = 0, recall = 0, precision = 0, mrr = 0;
        double worst = Double.MAX_VALUE;
        int noRelevantHit = 0;
        int n = corpus.queries().size();

        for (JudgedQuery q : corpus.queries()) {
            List<Integer> ranked = rankWithModel(engine, q.query(), model);
            Map<Integer, Integer> relevance = relevanceByQuery.get(q.query());

            double queryNdcg = RankingMetrics.ndcgAt10(ranked, relevance);
            ndcg += queryNdcg;
            worst = Math.min(worst, queryNdcg);
            if (queryNdcg <= 0.0) {
                noRelevantHit++;
            }

            RankingMetrics.Scores s = RankingMetrics.evaluate(ranked, relevance);
            map += s.map();
            recall += s.recallAt5();
            precision += s.precisionAt5();
            mrr += s.mrr();
        }

        return new QualityReport(ndcg / n, map / n, recall / n, precision / n, mrr / n,
                worst == Double.MAX_VALUE ? 0.0 : worst, noRelevantHit, n);
    }

    /** Serves top-K through the shared retrieval stage and global ranking pipeline. */
    private static List<Integer> rankWithModel(SearchEngine engine, String query, LinearRankingModel model) {
        RetrievalResult retrieval = engine.retrieveCandidates(query, SERVED_K);
        List<RankedDocument> rankedDocs = retrieval.ranked();
        if (model == null) {
            return rankedDocs.stream().map(RankedDocument::documentId).toList();
        }
        NormalizationContext context = engine.normalizationContext();
        List<RankedCandidate> candidates = new ArrayList<>(rankedDocs.size());
        for (RankedDocument d : rankedDocs) {
            RawFeatures raw = engine.rawFeatures(query, d.documentId());
            candidates.add(new RankedCandidate(
                    String.valueOf(d.documentId()), d.url(), d.title(), d.snippet(),
                    d.bm25Score(), d.pageRankScore(), raw));
        }
        return GlobalRankingPipeline.rank(query, candidates, context, model).stream()
                .map(r -> Integer.parseInt(r.candidate().documentId()))
                .toList();
    }

    private static Map<String, Map<Integer, Integer>> relevanceByQuery(JudgedCorpus corpus) {
        Map<String, Map<Integer, Integer>> byQuery = new HashMap<>();
        for (JudgedQuery q : corpus.queries()) {
            Map<Integer, Integer> graded = new HashMap<>();
            for (Map.Entry<String, Integer> e : q.urlToGrade().entrySet()) {
                Integer docId = corpus.urlToDocId().get(e.getKey());
                if (docId != null) {
                    graded.put(docId, e.getValue());
                }
            }
            byQuery.put(q.query(), graded);
        }
        return byQuery;
    }
}
