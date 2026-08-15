package com.minigoogle.performance;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.ranking.snippet.SnippetGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolates the cost of the lexical ranking stage ({@link RankingPipeline#rank})
 * as a function of the <em>candidate</em> count, holding {@code topK} fixed.
 *
 * <p>Motivation: {@code rank()} selects the best {@code topK} documents with a
 * min-heap, but every stage before that heap runs once per <em>candidate</em>.
 * If per-candidate work is expensive, ranking latency scales with the size of
 * the matched posting union rather than with the number of results actually
 * returned. These benchmarks measure that scaling directly, and attribute the
 * share of it that comes from snippet construction.</p>
 *
 * <p>The corpus here is deliberately synthetic and controlled: fixed body
 * length, fixed term count, deterministic seed. That isolates the scaling law
 * from corpus-specific noise. End-to-end numbers on the real search path live
 * in {@link SearchPerformanceBenchmarks}.</p>
 */
class RankingStageBenchmarks {

    private static final int BODY_CHARS = 2_000;
    private static final int TOP_K = 20;
    private static final List<String> QUERY_TERMS = List.of("alpha", "beta", "gamma");

    /** Deterministic corpus: {@code docCount} bodies of ~{@link #BODY_CHARS} chars. */
    private record Corpus(Map<Integer, String> urls,
                          Map<Integer, String> titles,
                          Map<Integer, String> bodies,
                          Map<Integer, Integer> lengths,
                          Map<Integer, Double> pageRanks) {
    }

    private static Corpus buildCorpus(int docCount) {
        // A small vocabulary that includes the query terms, so candidates carry
        // realistic term hits and the snippet scan has real matches to find.
        String[] vocab = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta",
                "network", "storage", "cluster", "index", "query", "document",
                "retrieval", "ranking", "distributed", "consensus"};
        Random rnd = new Random(20260815L);

        Map<Integer, String> urls = new HashMap<>(docCount);
        Map<Integer, String> titles = new HashMap<>(docCount);
        Map<Integer, String> bodies = new HashMap<>(docCount);
        Map<Integer, Integer> lengths = new HashMap<>(docCount);
        Map<Integer, Double> pageRanks = new HashMap<>(docCount);

        for (int id = 0; id < docCount; id++) {
            StringBuilder body = new StringBuilder(BODY_CHARS + 16);
            int words = 0;
            while (body.length() < BODY_CHARS) {
                body.append(vocab[rnd.nextInt(vocab.length)]).append(' ');
                words++;
            }
            urls.put(id, "https://host" + (id % 50) + ".example.com/doc/" + id);
            titles.put(id, "Document " + id);
            bodies.put(id, body.toString());
            lengths.put(id, words);
            pageRanks.put(id, rnd.nextDouble());
        }
        return new Corpus(urls, titles, bodies, lengths, pageRanks);
    }

    private static double avgLength(Corpus corpus) {
        return corpus.lengths().values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(1.0);
    }

    /** One posting list per query term, each covering all {@code docCount} candidates. */
    private static Map<String, PostingList> buildPostings(int docCount) {
        Random rnd = new Random(99L);
        Map<String, PostingList> postings = new HashMap<>();
        for (String term : QUERY_TERMS) {
            PostingList pl = new PostingList();
            for (int id = 0; id < docCount; id++) {
                pl.addPosting(new Posting(id, 1 + rnd.nextInt(5), List.of()));
            }
            postings.put(term, pl);
        }
        return postings;
    }

    private static BenchmarkReport measureRank(String name,
                                               RankingPipeline pipeline,
                                               Map<String, PostingList> postings,
                                               Map<String, Integer> dfs,
                                               int warmup,
                                               int iterations) {
        for (int i = 0; i < warmup; i++) {
            pipeline.rank(QUERY_TERMS, postings, dfs);
        }
        List<Long> latencies = new ArrayList<>(iterations);
        long wallStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            List<RankedDocument> out = pipeline.rank(QUERY_TERMS, postings, dfs);
            latencies.add(System.nanoTime() - t0);
            assertFalse(out.isEmpty(), "ranking must return results");
        }
        return new BenchmarkReport(name, iterations, latencies,
                Duration.ofNanos(System.nanoTime() - wallStart));
    }

    /**
     * Measures how ranking latency scales with candidate count at a fixed
     * {@code topK}. Ideal behavior for a top-k selector is near-flat growth in
     * the expensive per-document work; the printed table shows what actually
     * happens.
     */
    @Test
    void rankingLatencyScalesWithCandidateCount() {
        System.out.println("=== Ranking stage latency vs candidate count (topK=" + TOP_K
                + ", body=" + BODY_CHARS + " chars, " + QUERY_TERMS.size() + " terms) ===");

        double firstPerDocUs = -1;
        double lastPerDocUs = -1;

        for (int docCount : new int[]{200, 1_000, 5_000}) {
            Corpus corpus = buildCorpus(docCount);
            RankingPipeline pipeline = new RankingPipeline(
                    BM25Parameters.withDefaults(docCount, avgLength(corpus)),
                    corpus.pageRanks(), corpus.urls(), corpus.titles(),
                    corpus.bodies(), corpus.lengths(), TOP_K);

            Map<String, PostingList> postings = buildPostings(docCount);
            Map<String, Integer> dfs = new HashMap<>();
            for (String term : QUERY_TERMS) {
                dfs.put(term, docCount);
            }

            // Larger candidate sets are slower, so scale iterations down to keep
            // total benchmark wall time bounded while staying statistically usable.
            int iterations = docCount <= 1_000 ? 50 : 20;
            BenchmarkReport report = measureRank(
                    "rank-" + docCount, pipeline, postings, dfs, 10, iterations);

            double perDocUs = (report.p50LatencyMs() * 1_000.0) / docCount;
            if (firstPerDocUs < 0) {
                firstPerDocUs = perDocUs;
            }
            lastPerDocUs = perDocUs;

            System.out.printf("  candidates=%5d  p50=%8.2fms  p95=%8.2fms  p99=%8.2fms  perCandidate=%.2fus%n",
                    docCount, report.p50LatencyMs(), report.p95LatencyMs(),
                    report.p99LatencyMs(), perDocUs);
        }

        System.out.printf("  per-candidate cost: %.2fus at 200 -> %.2fus at 5000%n",
                firstPerDocUs, lastPerDocUs);
        assertTrue(lastPerDocUs > 0, "benchmark must produce a positive per-candidate cost");
    }

    /**
     * Measures learning-to-rank feature extraction, which runs once per served
     * document ({@code FeatureExtractor.extractRaw}). At a typical serving depth
     * of 20 candidates this executes 20 times per query, so its per-call cost is
     * multiplied by the result depth.
     */
    @Test
    void featureExtractionCostPerServedDocument() {
        int docCount = 500;
        int servedDepth = 20;
        Corpus corpus = buildCorpus(docCount);

        com.minigoogle.ml.features.FeatureExtractor extractor =
                new com.minigoogle.ml.features.FeatureExtractor(
                        corpus.urls(), corpus.titles(), corpus.bodies(),
                        corpus.lengths(), corpus.pageRanks(), null, null);

        String query = "alpha beta gamma";

        for (int i = 0; i < 200; i++) {
            extractor.extractRaw(query, i % docCount);
        }

        int iterations = 200;
        List<Long> latencies = new ArrayList<>(iterations);
        long wallStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            for (int d = 0; d < servedDepth; d++) {
                extractor.extractRaw(query, (i * servedDepth + d) % docCount);
            }
            latencies.add(System.nanoTime() - t0);
        }
        BenchmarkReport report = new BenchmarkReport("feature-extraction", iterations,
                latencies, Duration.ofNanos(System.nanoTime() - wallStart));

        System.out.println("=== LTR feature extraction (" + servedDepth
                + " served docs/query, body=" + BODY_CHARS + " chars) ===");
        System.out.printf("  per query (%d docs) p50=%.3fms p95=%.3fms p99=%.3fms%n",
                servedDepth, report.p50LatencyMs(), report.p95LatencyMs(), report.p99LatencyMs());
        System.out.printf("  per document        %.1fus%n",
                report.p50LatencyMs() * 1000.0 / servedDepth);

        assertTrue(report.p50LatencyMs() > 0, "feature extraction must be measurable");
    }

    /**
     * Attributes the ranking-stage cost: total {@code rank()} latency versus the
     * cost of generating snippets for the same candidate set. A snippet share
     * near 100% means the stage is dominated by work whose results are discarded
     * for all but {@code topK} documents.
     */
    @Test
    void snippetGenerationShareOfRankingCost() {
        int docCount = 2_000;
        Corpus corpus = buildCorpus(docCount);
        RankingPipeline pipeline = new RankingPipeline(
                BM25Parameters.withDefaults(docCount, avgLength(corpus)),
                corpus.pageRanks(), corpus.urls(), corpus.titles(),
                corpus.bodies(), corpus.lengths(), TOP_K);

        Map<String, PostingList> postings = buildPostings(docCount);
        Map<String, Integer> dfs = new HashMap<>();
        for (String term : QUERY_TERMS) {
            dfs.put(term, docCount);
        }

        BenchmarkReport rankReport = measureRank("rank-attrib", pipeline, postings, dfs, 10, 20);

        // Same candidate set, snippet construction only.
        SnippetGenerator generator = new SnippetGenerator();
        for (int i = 0; i < 3; i++) {
            for (int id = 0; id < docCount; id++) {
                generator.generate(corpus.bodies().get(id), QUERY_TERMS);
            }
        }
        List<Long> snippetLatencies = new ArrayList<>(20);
        long wallStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            for (int id = 0; id < docCount; id++) {
                generator.generate(corpus.bodies().get(id), QUERY_TERMS);
            }
            snippetLatencies.add(System.nanoTime() - t0);
        }
        BenchmarkReport snippetReport = new BenchmarkReport("snippets-all-candidates", 20,
                snippetLatencies, Duration.ofNanos(System.nanoTime() - wallStart));

        // Cost of snippets for only the documents actually returned.
        List<Long> topKLatencies = new ArrayList<>(20);
        wallStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            for (int id = 0; id < TOP_K; id++) {
                generator.generate(corpus.bodies().get(id), QUERY_TERMS);
            }
            topKLatencies.add(System.nanoTime() - t0);
        }
        BenchmarkReport topKReport = new BenchmarkReport("snippets-topk", 20,
                topKLatencies, Duration.ofNanos(System.nanoTime() - wallStart));

        double share = 100.0 * snippetReport.p50LatencyMs() / rankReport.p50LatencyMs();

        System.out.println("=== Ranking cost attribution (candidates=" + docCount
                + ", topK=" + TOP_K + ") ===");
        System.out.printf("  full rank()              p50=%8.2fms%n", rankReport.p50LatencyMs());
        System.out.printf("  snippets, all candidates p50=%8.2fms  (%.1f%% of rank)%n",
                snippetReport.p50LatencyMs(), share);
        System.out.printf("  snippets, topK only      p50=%8.2fms%n", topKReport.p50LatencyMs());
        System.out.printf("  wasted snippet work: %.2fms per query (%d of %d discarded)%n",
                snippetReport.p50LatencyMs() - topKReport.p50LatencyMs(),
                docCount - TOP_K, docCount);

        assertTrue(rankReport.p50LatencyMs() > 0, "rank latency must be measurable");
    }
}
