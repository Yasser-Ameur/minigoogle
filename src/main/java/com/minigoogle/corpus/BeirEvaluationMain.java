package com.minigoogle.corpus;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Runs the standard BEIR retrieval evaluation over a built index: for every
 * query with judgments in the given split, retrieves a top-K ranking and scores
 * NDCG@10, Recall@100, MRR@10 and MAP@100 against the qrels.
 *
 * <p>Multiple retrieval variants can be scored in one process so the numbers
 * are directly comparable (identical corpus, queries, judgments, and candidate
 * cutoff): {@code hybrid} (BM25 + exact semantic fusion) and {@code bm25}
 * (lexical only). PageRank and diversification are disabled and the vector
 * index is exact, matching the documented evaluation protocol.</p>
 *
 * <pre>
 *   BeirEvaluationMain --dataset trec-covid --dir data/beir/trec-covid
 *                      --out build/beir-index [--split test] [--topK 100]
 *                      [--variants hybrid,bm25] [--maxDocs 25000]
 *                      [--config ranking.topK=100] ...
 * </pre>
 */
public final class BeirEvaluationMain {

    private BeirEvaluationMain() {
    }

    public static void main(String[] args) throws IOException {
        configureLogging();
        CliOptions options = CliOptions.parse(args);
        long startNanos = System.nanoTime();

        System.out.println("Loading BEIR corpus '" + options.dataset + "' from " + options.dir);
        BeirCorpus corpus = BeirIngestionPipeline.load(options.dir, options.dataset,
                options.maxDocs, options.workDir, progress());

        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels(options.split);
        System.out.println("Corpus: " + corpus.docs().size() + " docs, " + corpus.queries().size()
                + " queries, split='" + options.split + "': " + qrels.size()
                + " judged queries, " + corpus.resolvedRelJudgments(options.split)
                + " resolved judgments (docs=" + corpus.stats().lines() + ")");

        Files.createDirectories(options.out);

        for (String variant : options.variants) {
            Map<String, String> overrides = new HashMap<>(options.configOverrides);
            if ("bm25".equals(variant)) {
                overrides.put("semantic.hybrid.enabled", "false");
            }
            Configuration config = buildConfig(overrides);

            long buildStart = System.nanoTime();
            SearchEngineBuild build = SearchEngineBuilder.build(corpus.docs(), config, options.out);
            long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;
            SearchEngine engine = build.engine();

            Map<String, double[]> perQuery = new TreeMap<>();
            int judged = 0;
            int evaluated = 0;
            for (BeirQuery q : corpus.queries()) {
                Map<Integer, Integer> rel = qrels.getOrDefault(q.id(), Map.of());
                if (rel.isEmpty()) {
                    continue;
                }
                if (options.maxQueries > 0 && evaluated >= options.maxQueries) {
                    break;
                }
                evaluated++;
                long t0 = System.nanoTime();
                List<RankedDocument> rankedDocs = engine.retrieveCandidates(q.text(), options.topK).ranked();
                long queryMillis = (System.nanoTime() - t0) / 1_000_000;
                if (options.debugQuery != null && options.debugQuery.equals(q.id())) {
                    System.out.println("  [debug] query '" + q.id() + "' text='" + q.text()
                            + "' retrieve=" + queryMillis + " ms, ranked=" + rankedDocs.size());
                    System.out.println("  [debug] judged docs: " + rel);
                    int limit = Math.min(20, rankedDocs.size());
                    for (int i = 0; i < limit; i++) {
                        RankedDocument r = rankedDocs.get(i);
                        Integer grade = rel.get(r.documentId());
                        System.out.println(String.format("  [debug]   #%d docId=%d bm25=%.4f fused=%.4f rel=%s title=%s",
                                i + 1, r.documentId(), r.bm25Score(), r.finalScore(),
                                grade == null ? "-" : grade,
                                r.title().length() > 60 ? r.title().substring(0, 60) : r.title()));
                    }
                }
                List<Integer> ranked = rankedDocs.stream()
                        .map(RankedDocument::documentId)
                        .toList();
                double[] m = new double[4];
                m[0] = RankingMetrics.ndcgAt(ranked, rel, 10);
                m[1] = RankingMetrics.recallAtK(ranked, rel, options.topK);
                m[2] = RankingMetrics.mrrAtK(ranked, rel, 10);
                m[3] = RankingMetrics.mapAtK(ranked, rel, options.topK);
                perQuery.put(q.id(), m);
                judged++;
            }

            double ndcg = 0, recall = 0, mrr = 0, map = 0;
            for (double[] m : perQuery.values()) {
                ndcg += m[0];
                recall += m[1];
                mrr += m[2];
                map += m[3];
            }
            double n = Math.max(1, judged);
            System.out.println();
            System.out.println("=== variant: " + variant + " ===");
            System.out.println("  index build time : " + buildMillis + " ms");
            System.out.println("  judged queries   : " + judged + " of " + corpus.queries().size());
            System.out.println(String.format(
                    "  NDCG@10=%.4f  Recall@%d=%.4f  MRR@10=%.4f  MAP@%d=%.4f",
                    ndcg / n, options.topK, recall / n, mrr / n, options.topK, map / n));
        }

        long totalMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.println();
        System.out.println("Total time: " + totalMillis + " ms");
    }

    private static Consumer<BeirCorpusReader.Progress> progress() {
        return p -> System.out.println("  " + p);
    }

    private static Configuration buildConfig(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>();
        // Evaluation protocol defaults for BEIR corpora (no links, one domain).
        props.put("ranking.pagerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.topK", "100");
        props.put("semantic.hybrid.fetchK", "100");
        props.put("semantic.enabled", "true");
        props.put("semantic.hybrid.enabled", "true");
        props.put("semantic.expansion.enabled", "false");
        props.put("semantic.index.mode", "flat");
        props.putAll(overrides);
        return new Configuration(props);
    }

    private static void configureLogging() {
        ch.qos.logback.classic.Logger mini = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("com.minigoogle");
        mini.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    private static final class CliOptions {
        String dataset = "trec-covid";
        Path dir;
        Path out = Path.of("build/beir-index");
        Path workDir;
        String split = "test";
        int topK = 100;
        int maxDocs = 0;
        int maxQueries = 0;
        String debugQuery;
        List<String> variants = List.of("hybrid", "bm25");
        final Map<String, String> configOverrides = new HashMap<>();

        static CliOptions parse(String[] args) {
            CliOptions o = new CliOptions();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--dataset" -> o.dataset = args[++i];
                    case "--dir" -> o.dir = Path.of(args[++i]);
                    case "--out" -> o.out = Path.of(args[++i]);
                    case "--work" -> o.workDir = Path.of(args[++i]);
                    case "--split" -> o.split = args[++i];
                    case "--topK" -> o.topK = Integer.parseInt(args[++i]);
                    case "--maxDocs" -> o.maxDocs = Integer.parseInt(args[++i]);
                    case "--maxQueries" -> o.maxQueries = Integer.parseInt(args[++i]);
                    case "--debugQuery" -> o.debugQuery = args[++i];
                    case "--variants" -> o.variants = List.of(args[++i].split(","));
                    case "--config" -> {
                        String kv = args[++i];
                        int eq = kv.indexOf('=');
                        if (eq <= 0) {
                            throw new IllegalArgumentException("--config expects key=value, got '" + kv + "'");
                        }
                        o.configOverrides.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                    default -> throw new IllegalArgumentException(
                            "Unknown argument '" + args[i] + "' (use --dataset/--dir/--out/--work/--split/--topK/--maxDocs/--debugQuery/--variants/--config)");
                }
            }
            if (o.dir == null) {
                throw new IllegalArgumentException("Missing required --dir <dataset directory>");
            }
            if (o.workDir == null) {
                o.workDir = o.out.resolve("work");
            }
            o.variants = new ArrayList<>(o.variants);
            o.variants.sort(Comparator.naturalOrder());
            return o;
        }
    }
}
