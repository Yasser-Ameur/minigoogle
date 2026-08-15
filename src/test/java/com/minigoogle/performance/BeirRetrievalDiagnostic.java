package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC — instruments the production retrieval path on a real BEIR corpus.
 *
 * <p>Not a pass/fail test: it reports, per query, how many results the engine
 * actually returns and what that costs. The BEIR harness reports only aggregate
 * metrics, which cannot distinguish "ranked the wrong documents" from "returned
 * nothing at all" — and those call for completely different fixes.</p>
 *
 * <p>Disabled unless {@code -Dbeir.dir} is set, since it needs the dataset on
 * disk and takes minutes to build the index.</p>
 *
 * <pre>
 *   gradlew bench --tests "*BeirRetrievalDiagnostic" \
 *     -Dbeir.dir=data/beir/trec-covid -Dbeir.dataset=trec-covid
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class BeirRetrievalDiagnostic {

    @Test
    void reportPerQueryRetrievalBehaviour() throws IOException {
        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "trec-covid");
        int topK = Integer.parseInt(System.getProperty("beir.topK", "100"));
        Path out = Path.of(System.getProperty("beir.out", "build/beir-diag/" + dataset));
        Files.createDirectories(out);

        System.out.println("=== Loading " + dataset + " from " + dir + " ===");
        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, 0,
                out.resolve("work"), progressEvent -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");
        System.out.println("corpus=" + corpus.docs().size() + " docs, judged queries=" + qrels.size());

        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.topK", String.valueOf(topK));
        props.put("search.topK", String.valueOf(topK));
        props.put("ranking.diversify.enabled", "false");

        long buildStart = System.nanoTime();
        SearchEngine engine = SearchEngineBuilder
                .build(corpus.docs(), new Configuration(props), out.resolve("index"))
                .engine();
        System.out.printf("index build: %.1fs%n", (System.nanoTime() - buildStart) / 1e9);

        int empty = 0;
        int belowTopK = 0;
        List<Long> latencies = new ArrayList<>();
        List<Integer> resultCounts = new ArrayList<>();
        double ndcgSum = 0;
        double recallSum = 0;
        int evaluated = 0;

        System.out.println();
        System.out.println("qid | terms | returned | latencyMs | ndcg@10 | recall@100 | query");
        for (BeirQuery q : corpus.queries()) {
            Map<Integer, Integer> rel = qrels.getOrDefault(q.id(), Map.of());
            if (rel.isEmpty()) {
                continue;
            }
            evaluated++;

            long t0 = System.nanoTime();
            List<RankedDocument> ranked = engine.retrieveCandidates(q.text(), topK).ranked();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            latencies.add(ms);
            resultCounts.add(ranked.size());
            if (ranked.isEmpty()) {
                empty++;
            }
            if (ranked.size() < topK) {
                belowTopK++;
            }

            List<Integer> ids = ranked.stream().map(RankedDocument::documentId).toList();
            double ndcg = RankingMetrics.ndcgAt(ids, rel, 10);
            double recall = RankingMetrics.recallAtK(ids, rel, topK);
            ndcgSum += ndcg;
            recallSum += recall;

            int terms = q.text().trim().split("\\s+").length;
            if (evaluated <= 12) {
                System.out.printf("%-4s| %5d | %8d | %9d | %.4f  | %.4f     | %s%n",
                        q.id(), terms, ranked.size(), ms, ndcg, recall,
                        q.text().length() > 50 ? q.text().substring(0, 50) : q.text());
            }
        }

        latencies.sort(Long::compare);
        resultCounts.sort(Integer::compare);

        System.out.println();
        System.out.println("=== Summary over " + evaluated + " judged queries (topK=" + topK + ") ===");
        System.out.printf("  queries returning ZERO results : %d / %d%n", empty, evaluated);
        System.out.printf("  queries returning < topK       : %d / %d%n", belowTopK, evaluated);
        System.out.printf("  results returned  min=%d median=%d max=%d%n",
                resultCounts.get(0),
                resultCounts.get(resultCounts.size() / 2),
                resultCounts.get(resultCounts.size() - 1));
        System.out.printf("  latency ms        p50=%d p95=%d p99=%d max=%d%n",
                latencies.get(latencies.size() / 2),
                latencies.get((int) (latencies.size() * 0.95)),
                latencies.get((int) (latencies.size() * 0.99)),
                latencies.get(latencies.size() - 1));
        System.out.printf("  NDCG@10  = %.4f%n", ndcgSum / evaluated);
        System.out.printf("  Recall@%d = %.4f%n", topK, recallSum / evaluated);

        assertTrue(evaluated > 0, "the diagnostic must evaluate at least one query");
    }
}
