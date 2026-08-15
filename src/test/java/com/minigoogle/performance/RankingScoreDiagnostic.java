package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC — where does the ranking function place relevant documents, and
 * what do the score components actually look like?
 *
 * <p>Reports the rank distribution, the theoretical ceiling each cutoff imposes
 * given how many documents are judged relevant, and the distributions of the
 * BM25 and PageRank components alongside the fused score. The ceiling matters:
 * TREC-COVID judges hundreds of documents relevant per query, so a low
 * {@code Recall@100} can be arithmetic rather than a ranking failure, and
 * treating it as the latter would send the next change in the wrong direction.</p>
 *
 * <pre>
 *   gradlew bench --tests "*RankingScoreDiagnostic" \
 *     -Dbeir.dir=data/beir/trec-covid -Dbeir.dataset=trec-covid -Dbeir.pagerank=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class RankingScoreDiagnostic {

    @Test
    void reportRankAndScoreDistributions() throws IOException {
        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "trec-covid");
        int deepK = Integer.parseInt(System.getProperty("beir.deepK", "1000"));
        String pagerank = System.getProperty("beir.pagerank", "true");
        Path out = Path.of(System.getProperty("beir.out", "build/beir-rank/" + dataset));
        Files.createDirectories(out);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, 0, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        Map<String, String> props = new HashMap<>();
        String semantic = System.getProperty("beir.semantic", "false");
        String hybrid = System.getProperty("beir.hybrid", "false");
        props.put("semantic.enabled", semantic);
        props.put("semantic.hybrid.enabled", hybrid);
        props.put("semantic.index.mode", "flat");
        String expansion = System.getProperty("beir.expansion", "false");
        props.put("semantic.expansion.enabled", expansion);
        props.put("ranking.topK", String.valueOf(deepK));
        props.put("search.topK", String.valueOf(deepK));
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.rerank.enabled", "false");
        props.put("ranking.pagerank.enabled", pagerank);

        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), out.resolve("index"));
        SearchEngine engine = build.engine();
        Map<Integer, Double> pageRankScores = build.pageRankScores();

        System.out.println("=== Ranking diagnosis: " + dataset + " (" + corpus.docs().size()
                + " docs, deepK=" + deepK + ", pagerank=" + pagerank
                + ", expansion=" + expansion + ", semantic=" + semantic
                + ", hybrid=" + hybrid + ") ===");

        // ── PageRank component: is it a real signal on this corpus? ──
        Set<Double> distinctPageRank = new HashSet<>(pageRankScores.values());
        System.out.printf("PageRank map: %d entries, %d distinct values%n",
                pageRankScores.size(), distinctPageRank.size());
        if (distinctPageRank.size() <= 3) {
            System.out.println("  -> PageRank carries no discriminating signal on this corpus "
                    + "(BEIR documents have no outgoing links), so after min-max normalization "
                    + "it contributes a constant to every candidate and cannot reorder anything.");
        }

        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (String b : List.of("1-10", "11-100", "101-1000", "1001-5000", "5000+", "never")) {
            buckets.put(b, 0);
        }

        List<Double> bm25Relevant = new ArrayList<>();
        List<Double> bm25NonRelevant = new ArrayList<>();
        List<Double> fusedRelevant = new ArrayList<>();
        double ndcgSum = 0, mrrSum = 0, r10 = 0, r100 = 0, r1000 = 0;
        double ceiling10 = 0, ceiling100 = 0;
        int evaluated = 0;
        int totalRelevant = 0;
        long candidateTotal = 0;
        List<String> perQuery = new ArrayList<>();

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
            totalRelevant += relevant.size();

            List<RankedDocument> ranked = engine.retrieveCandidates(q.text(), deepK).ranked();
            List<Integer> ids = ranked.stream().map(RankedDocument::documentId).toList();

            Map<Integer, Integer> rankOf = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                rankOf.put(ids.get(i), i + 1);
            }

            for (RankedDocument d : ranked) {
                if (relevant.contains(d.documentId())) {
                    bm25Relevant.add(d.bm25Score());
                    fusedRelevant.add(d.finalScore());
                } else if (bm25NonRelevant.size() < 200_000) {
                    bm25NonRelevant.add(d.bm25Score());
                }
            }

            for (int docId : relevant) {
                Integer r = rankOf.get(docId);
                String bucket;
                if (r == null) {
                    bucket = "never";
                } else if (r <= 10) {
                    bucket = "1-10";
                } else if (r <= 100) {
                    bucket = "11-100";
                } else if (r <= 1000) {
                    bucket = "101-1000";
                } else if (r <= 5000) {
                    bucket = "1001-5000";
                } else {
                    bucket = "5000+";
                }
                buckets.merge(bucket, 1, Integer::sum);
            }

            candidateTotal += ids.size();
            ndcgSum += RankingMetrics.ndcgAt(ids, rel, 10);
            mrrSum += RankingMetrics.mrrAtK(ids, rel, 10);
            r10 += RankingMetrics.recallAtK(ids, rel, 10);
            r100 += RankingMetrics.recallAtK(ids, rel, 100);
            r1000 += RankingMetrics.recallAtK(ids, rel, deepK);

            // Arithmetic ceiling: a top-K run cannot exceed K/|relevant| recall.
            ceiling10 += Math.min(1.0, 10.0 / relevant.size());
            ceiling100 += Math.min(1.0, 100.0 / relevant.size());

            if (perQuery.size() < 12) {
                perQuery.add(String.format("%-5s rel=%-5d R@100=%.4f ceil@100=%.4f ndcg=%.4f  %s",
                        q.id(), relevant.size(),
                        RankingMetrics.recallAtK(ids, rel, 100),
                        Math.min(1.0, 100.0 / relevant.size()),
                        RankingMetrics.ndcgAt(ids, rel, 10),
                        q.text().length() > 38 ? q.text().substring(0, 38) : q.text()));
            }
        }

        System.out.println();
        System.out.printf("queries=%d  mean relevant per query=%.1f%n",
                evaluated, (double) totalRelevant / evaluated);
        System.out.printf("mean returned per query=%.1f%n", (double) candidateTotal / evaluated);
        System.out.printf("NDCG@10=%.4f  MRR@10=%.4f  R@10=%.4f  R@100=%.4f  R@%d=%.4f%n",
                ndcgSum / evaluated, mrrSum / evaluated, r10 / evaluated,
                r100 / evaluated, deepK, r1000 / evaluated);

        System.out.println();
        System.out.println("--- Recall ceilings imposed by the number of judged relevant docs ---");
        System.out.printf("  max achievable R@10  = %.4f   (measured %.4f, %.1f%% of ceiling)%n",
                ceiling10 / evaluated, r10 / evaluated,
                100.0 * (r10 / evaluated) / (ceiling10 / evaluated));
        System.out.printf("  max achievable R@100 = %.4f   (measured %.4f, %.1f%% of ceiling)%n",
                ceiling100 / evaluated, r100 / evaluated,
                100.0 * (r100 / evaluated) / (ceiling100 / evaluated));

        int totalJudged = buckets.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println();
        System.out.println("--- Rank distribution over " + totalJudged + " relevant judgments ---");
        buckets.forEach((b, c) -> System.out.printf("  %-12s %7d  %5.1f%%%n",
                b, c, totalJudged == 0 ? 0 : 100.0 * c / totalJudged));

        System.out.println();
        System.out.println("--- Score separation ---");
        printStats("BM25 relevant    ", bm25Relevant);
        printStats("BM25 non-relevant", bm25NonRelevant);
        printStats("fused relevant   ", fusedRelevant);

        System.out.println();
        System.out.println("--- Sample queries ---");
        perQuery.forEach(l -> System.out.println("  " + l));

        assertTrue(evaluated > 0);
    }

    private static void printStats(String label, List<Double> values) {
        if (values.isEmpty()) {
            System.out.printf("  %s (empty)%n", label);
            return;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.printf("  %s n=%-7d min=%.3f p50=%.3f p90=%.3f p99=%.3f max=%.3f mean=%.3f%n",
                label, sorted.size(), sorted.get(0),
                sorted.get((int) (sorted.size() * 0.50)),
                sorted.get((int) (sorted.size() * 0.90)),
                sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99))),
                sorted.get(sorted.size() - 1), mean);
    }
}
