package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.ranking.fusion.ReciprocalRankFusion;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.semantic.encoder.SemanticRetriever;
import com.minigoogle.semantic.encoder.SentenceEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 vs semantic vs union vs RRF, under one ranking configuration.
 *
 * <p>The decisive comparison is <b>union vs RRF</b>: both see the same candidate
 * set, so any difference between them is ordering and not recall. The union
 * established that semantic candidates reach the pool but never the top-100,
 * because the lexical ranker has no signal for a document with no query-term
 * overlap.</p>
 *
 * <p>Vectors are cached under {@code models/vectors/} rather than {@code build/}
 * — they cost 3–27 minutes to compute and {@code gradlew clean} would otherwise
 * delete them between experiments.</p>
 *
 * <pre>
 *   gradlew bench --tests "*RrfHybridDiagnostic" \
 *     -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class RrfHybridDiagnostic {

    private static final Path MODEL_DIR = Path.of("models", "all-MiniLM-L6-v2");
    private static final Path VECTOR_DIR = Path.of("models", "vectors");

    private static final int CANONICAL_K = ReciprocalRankFusion.DEFAULT_K;
    private static final int CANONICAL_DEPTH = 1000;
    private static final int[] RRF_KS = {10, 20, 40, 60, 100};
    private static final int[] SEMANTIC_DEPTHS = {100, 500, 1000};
    /** Simulates {@code ranking.topK}: production truncates the lexical ranking to it. */
    private static final int[] LEXICAL_DEPTHS = {20, 100, 1000};
    private static final int[] INTERACTION_KS = {10, 60, 100};

    /** {ndcg10, mrr10, r10, r100, r1000, candidateRecall} */
    private static double[] metrics(List<Integer> ranked, Map<Integer, Integer> rel,
                                    Set<Integer> relevant, Set<Integer> candidates) {
        long inCandidates = relevant.stream().filter(candidates::contains).count();
        return new double[]{
                RankingMetrics.ndcgAt(ranked, rel, 10),
                RankingMetrics.mrrAtK(ranked, rel, 10),
                RankingMetrics.recallAtK(ranked, rel, 10),
                RankingMetrics.recallAtK(ranked, rel, 100),
                RankingMetrics.recallAtK(ranked, rel, 1000),
                (double) inCandidates / relevant.size()
        };
    }

    private static void accumulate(double[] into, double[] row) {
        for (int i = 0; i < row.length; i++) {
            into[i] += row[i];
        }
    }

    private static void print(String label, double[] sums, int n) {
        System.out.printf("  %-26s NDCG@10=%.4f MRR@10=%.4f R@10=%.4f R@100=%.4f R@1000=%.4f candRecall=%.4f%n",
                label, sums[0] / n, sums[1] / n, sums[2] / n, sums[3] / n, sums[4] / n, sums[5] / n);
    }

    private static double percentileMillis(List<Long> nanos, double p) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compare);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx))) / 1e6;
    }

    private static void printLatency(String label, List<Long> nanos) {
        System.out.printf("  %-26s p50=%8.2fms  p95=%8.2fms  p99=%8.2fms%n", label,
                percentileMillis(nanos, 50), percentileMillis(nanos, 95), percentileMillis(nanos, 99));
    }

    /** One query's outcome, retained for the query-level case analysis. */
    private record QueryCase(String qid, String text, double bm25Ndcg, double semNdcg, double rrfNdcg,
                             int intersection10, int semanticOnlyRelevant, int semanticOnlyInRrfTop10,
                             List<Integer> bm25Top, List<Integer> semTop, List<Integer> unionTop,
                             List<Integer> rrfTop, Set<Integer> relevant) {
    }

    private static String mark(List<Integer> ids, Set<Integer> relevant, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, ids.size()); i++) {
            int id = ids.get(i);
            sb.append(i == 0 ? "" : " ").append(id).append(relevant.contains(id) ? "*" : "");
        }
        return sb.toString();
    }

    private static void printCase(String header, QueryCase c) {
        if (c == null) {
            System.out.println("  " + header + ": no query matched this pattern");
            return;
        }
        System.out.println("  " + header);
        System.out.printf("    query %s: %s%n", c.qid(),
                c.text().length() > 90 ? c.text().substring(0, 90) + "..." : c.text());
        System.out.printf("    NDCG@10  bm25=%.4f semantic=%.4f rrf=%.4f  (relevant=%d, intersection@10=%d)%n",
                c.bm25Ndcg(), c.semNdcg(), c.rrfNdcg(), c.relevant().size(), c.intersection10());
        System.out.printf("    bm25  top5: %s%n", mark(c.bm25Top(), c.relevant(), 5));
        System.out.printf("    sem   top5: %s%n", mark(c.semTop(), c.relevant(), 5));
        System.out.printf("    union top5: %s%n", mark(c.unionTop(), c.relevant(), 5));
        System.out.printf("    rrf   top5: %s%n", mark(c.rrfTop(), c.relevant(), 5));
        System.out.println("    (* = judged relevant)");
    }

    @Test
    void bm25VersusSemanticVersusUnionVersusRrf() throws Exception {
        assertTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model missing at " + MODEL_DIR);

        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "scifact");
        int deepK = Integer.parseInt(System.getProperty("beir.deepK", "1000"));
        int maxDocs = Integer.parseInt(System.getProperty("beir.maxDocs", "0"));
        int threads = Integer.parseInt(System.getProperty("beir.threads", "8"));
        Path out = Path.of("build", "beir-rrf", dataset);
        Files.createDirectories(out);
        Files.createDirectories(VECTOR_DIR);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, maxDocs, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

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

        String label = dataset + (maxDocs > 0 ? " DIAGNOSTIC SUBSET" : " FULL CORPUS");
        System.out.println("=== BM25 / semantic / union / RRF: " + label
                + " (" + corpus.docs().size() + " docs, deepK=" + deepK + ") ===");

        Path vectorFile = VECTOR_DIR.resolve(dataset + "-" + corpus.docs().size() + "-minilm.bin");
        SentenceEncoder encoder = SentenceEncoder.load(
                MODEL_DIR, SentenceEncoder.DEFAULT_MAX_TOKENS, SentenceEncoder.MINILM_DIMENSION, threads);

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
            double seconds = (System.nanoTime() - t0) / 1e9;
            System.out.printf("VECTOR BUILD: %.1fs for %d docs = %.1f docs/s -> %.1f MB%n",
                    seconds, corpus.docs().size(), corpus.docs().size() / seconds,
                    Files.size(vectorFile) / 1e6);
        } else {
            System.out.printf("reusing vector store %s (%.1f MB)%n",
                    vectorFile, Files.size(vectorFile) / 1e6);
        }

        try (SemanticRetriever semantic = SemanticRetriever.load(encoder, vectorFile)) {
            double[] bm25 = new double[6];
            double[] sem = new double[6];
            double[] union = new double[6];
            Map<Integer, double[]> byRrfK = new HashMap<>();
            for (int k : RRF_KS) {
                byRrfK.put(k, new double[6]);
            }
            Map<Integer, double[]> byDepth = new HashMap<>();
            for (int d : SEMANTIC_DEPTHS) {
                byDepth.put(d, new double[6]);
            }
            Map<Integer, double[]> byLexicalDepth = new HashMap<>();
            for (int d : LEXICAL_DEPTHS) {
                byLexicalDepth.put(d, new double[6]);
            }
            Map<String, double[]> interaction = new HashMap<>();

            List<Long> lexicalLatency = new ArrayList<>();
            List<Long> semanticLatency = new ArrayList<>();
            List<Long> fusionLatency = new ArrayList<>();
            List<Long> unionLatency = new ArrayList<>();
            List<Long> rrfLatency = new ArrayList<>();

            long semOnly = 0, unionTop10 = 0, unionTop100 = 0, unionTop1000 = 0;
            long rrfTop10 = 0, rrfTop100 = 0, rrfTop1000 = 0;
            double overlap10 = 0, overlap50 = 0, overlap100 = 0;
            double jaccard10 = 0, jaccard100 = 0;
            List<QueryCase> cases = new ArrayList<>();
            List<String> promotionExamples = new ArrayList<>();
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
                List<Integer> lexicalIds = engine.retrieveCandidates(q.text(), deepK).ranked()
                        .stream().map(RankedDocument::documentId).toList();
                long lexicalNanos = System.nanoTime() - t0;
                Set<Integer> lexicalSet = new HashSet<>(lexicalIds);

                t0 = System.nanoTime();
                List<Integer> semIds = semantic.retrieve(q.text(), Math.max(deepK, CANONICAL_DEPTH))
                        .stream().map(SemanticRetriever.Candidate::documentId).toList();
                long semanticNanos = System.nanoTime() - t0;

                lexicalLatency.add(lexicalNanos);
                semanticLatency.add(semanticNanos);

                double[] bm25Row = metrics(lexicalIds, rel, relevant, lexicalSet);
                double[] semRow = metrics(semIds, rel, relevant, new HashSet<>(semIds));
                accumulate(bm25, bm25Row);
                accumulate(sem, semRow);

                // C: union — BM25 order, then semantic-only ids appended.
                List<Integer> semCanonical = semIds.subList(0, Math.min(CANONICAL_DEPTH, semIds.size()));
                LinkedHashSet<Integer> unionSet = new LinkedHashSet<>(lexicalIds);
                unionSet.addAll(semCanonical);
                List<Integer> unionIds = new ArrayList<>(unionSet);
                accumulate(union, metrics(unionIds, rel, relevant, new HashSet<>(unionIds)));
                unionLatency.add(lexicalNanos + semanticNanos);

                // D: RRF, k sweep at canonical semantic depth.
                List<Integer> canonicalRrf = null;
                for (int k : RRF_KS) {
                    t0 = System.nanoTime();
                    List<Integer> fusedIds = new ReciprocalRankFusion(k).fuseToIds(lexicalIds, semCanonical);
                    long fusionNanos = System.nanoTime() - t0;
                    accumulate(byRrfK.get(k), metrics(fusedIds, rel, relevant, new HashSet<>(fusedIds)));
                    if (k == CANONICAL_K) {
                        canonicalRrf = fusedIds;
                        fusionLatency.add(fusionNanos);
                        rrfLatency.add(lexicalNanos + semanticNanos + fusionNanos);
                    }
                }

                // D: semantic depth sweep at canonical k, plus the interaction grid.
                for (int d : SEMANTIC_DEPTHS) {
                    List<Integer> semD = semIds.subList(0, Math.min(d, semIds.size()));
                    for (int k : INTERACTION_KS) {
                        List<Integer> cell = new ReciprocalRankFusion(k).fuseToIds(lexicalIds, semD);
                        double[] row = metrics(cell, rel, relevant, new HashSet<>(cell));
                        interaction.computeIfAbsent(d + "/" + k, key -> new double[6]);
                        accumulate(interaction.get(d + "/" + k), row);
                        if (k == CANONICAL_K) {
                            accumulate(byDepth.get(d), row);
                        }
                    }
                }

                // D: lexical depth sweep — this is what `ranking.topK` controls in
                // production, and its default (20) is far shallower than deepK.
                for (int d : LEXICAL_DEPTHS) {
                    List<Integer> lexD = lexicalIds.subList(0, Math.min(d, lexicalIds.size()));
                    List<Integer> fusedIds = new ReciprocalRankFusion(CANONICAL_K)
                            .fuseToIds(lexD, semCanonical);
                    accumulate(byLexicalDepth.get(d), metrics(fusedIds, rel, relevant, new HashSet<>(fusedIds)));
                }

                overlap10 += intersection(lexicalIds, semIds, 10);
                overlap50 += intersection(lexicalIds, semIds, 50);
                overlap100 += intersection(lexicalIds, semIds, 100);
                jaccard10 += jaccard(lexicalIds, semIds, 10);
                jaccard100 += jaccard(lexicalIds, semIds, 100);

                // Semantic-only relevant documents: where do they land?
                Set<Integer> semCanonicalSet = new HashSet<>(semCanonical);
                int semOnlyThisQuery = 0;
                int semOnlyInRrfTop10 = 0;
                for (int docId : relevant) {
                    if (lexicalSet.contains(docId) || !semCanonicalSet.contains(docId)) {
                        continue;
                    }
                    semOnly++;
                    semOnlyThisQuery++;
                    int semRank = semCanonical.indexOf(docId) + 1;
                    int u = unionIds.indexOf(docId) + 1;
                    int r = canonicalRrf.indexOf(docId) + 1;
                    if (u > 0 && u <= 1000) {
                        unionTop1000++;
                    }
                    if (u > 0 && u <= 100) {
                        unionTop100++;
                    }
                    if (u > 0 && u <= 10) {
                        unionTop10++;
                    }
                    if (r > 0 && r <= 1000) {
                        rrfTop1000++;
                    }
                    if (r > 0 && r <= 100) {
                        rrfTop100++;
                    }
                    if (r > 0 && r <= 10) {
                        rrfTop10++;
                        semOnlyInRrfTop10++;
                    }
                    if (promotionExamples.size() < 12) {
                        promotionExamples.add(String.format(
                                "    q=%-8s doc=%-7d semRank=%-5d bm25=absent unionPos=%-6d rrfRank=%d",
                                q.id(), docId, semRank, u, r));
                    }
                }

                cases.add(new QueryCase(q.id(), q.text(), bm25Row[0], semRow[0],
                        RankingMetrics.ndcgAt(canonicalRrf, rel, 10),
                        intersection(lexicalIds, semIds, 10),
                        semOnlyThisQuery, semOnlyInRrfTop10,
                        lexicalIds, semIds, unionIds, canonicalRrf, relevant));
            }

            System.out.println();
            System.out.println("--- Four systems (identical ranking configuration) ---");
            print("A: BM25", bm25, evaluated);
            print("B: semantic", sem, evaluated);
            print("C: union (semK=1000)", union, evaluated);
            print("D: RRF k=60 semK=1000", byRrfK.get(CANONICAL_K), evaluated);
            System.out.printf("  judged queries evaluated: %d%n", evaluated);

            System.out.println();
            System.out.println("--- Latency (NOT the headline; see caveat) ---");
            printLatency("A: BM25 retrieve+rank+snip", lexicalLatency);
            printLatency("B: semantic encode+scan", semanticLatency);
            printLatency("C: union (A+B)", unionLatency);
            printLatency("D: RRF (A+B+fuse)", rrfLatency);
            printLatency("   fusion step alone", fusionLatency);
            System.out.println("  CAVEAT: A includes snippet generation for topK=" + deepK
                    + " documents, so A and B are NOT like-for-like retrieval costs.");

            System.out.println();
            System.out.println("--- RRF k sweep (semK=1000, lexical depth=" + deepK + ") ---");
            for (int k : RRF_KS) {
                print("RRF k=" + k, byRrfK.get(k), evaluated);
            }

            System.out.println();
            System.out.println("--- Semantic depth sweep (RRF k=60) ---");
            for (int d : SEMANTIC_DEPTHS) {
                print("RRF semK=" + d, byDepth.get(d), evaluated);
            }

            System.out.println();
            System.out.println("--- Lexical depth sweep (RRF k=60, semK=1000) ---");
            System.out.println("  This is production's `ranking.topK`, whose default is 20.");
            for (int d : LEXICAL_DEPTHS) {
                print("RRF lexK=" + d, byLexicalDepth.get(d), evaluated);
            }

            System.out.println();
            System.out.println("--- Interaction: semantic depth x RRF k (NDCG@10) ---");
            System.out.printf("  %-12s %10s %10s %10s%n", "semK \\ k", "10", "60", "100");
            for (int d : SEMANTIC_DEPTHS) {
                System.out.printf("  %-12s %10.4f %10.4f %10.4f%n", String.valueOf(d),
                        interaction.get(d + "/10")[0] / evaluated,
                        interaction.get(d + "/60")[0] / evaluated,
                        interaction.get(d + "/100")[0] / evaluated);
            }

            System.out.println();
            System.out.println("--- Semantic-only relevant documents (found by semantic, missed by BM25) ---");
            System.out.printf("  total                    : %d%n", semOnly);
            System.out.printf("  union  -> top10/100/1000 : %d / %d / %d%n",
                    unionTop10, unionTop100, unionTop1000);
            System.out.printf("  RRF    -> top10/100/1000 : %d / %d / %d%n",
                    rrfTop10, rrfTop100, rrfTop1000);
            System.out.println("  examples (semantic rank -> union position -> RRF rank):");
            promotionExamples.forEach(System.out::println);

            System.out.println();
            System.out.println("--- Ranking overlap between BM25 and semantic ---");
            System.out.printf("  mean intersection@10=%.2f  @50=%.2f  @100=%.2f%n",
                    overlap10 / evaluated, overlap50 / evaluated, overlap100 / evaluated);
            System.out.printf("  mean Jaccard@10=%.4f  @100=%.4f%n",
                    jaccard10 / evaluated, jaccard100 / evaluated);

            System.out.println();
            System.out.println("--- Query-level cases (RRF k=60, semK=1000) ---");
            printCase("Case A: RRF promotes a semantic-only relevant document into the top 10",
                    cases.stream().filter(c -> c.semanticOnlyInRrfTop10() > 0)
                            .max(Comparator.comparingDouble(c -> c.rrfNdcg() - c.bm25Ndcg())).orElse(null));
            printCase("Case B: semantic finds relevant documents but RRF still ranks them low",
                    cases.stream().filter(c -> c.semanticOnlyRelevant() > 0 && c.semanticOnlyInRrfTop10() == 0)
                            .max(Comparator.comparingInt(QueryCase::semanticOnlyRelevant)).orElse(null));
            printCase("Case C: RRF hurts — semantic dilutes a good lexical ranking",
                    cases.stream().max(Comparator.comparingDouble(c -> c.bm25Ndcg() - c.rrfNdcg())).orElse(null));
            printCase("Case D: BM25 and semantic strongly agree",
                    cases.stream().max(Comparator.comparingInt(QueryCase::intersection10)).orElse(null));
            printCase("Case E: BM25 clearly better than semantic",
                    cases.stream().max(Comparator.comparingDouble(c -> c.bm25Ndcg() - c.semNdcg())).orElse(null));

            long rrfWins = cases.stream().filter(c -> c.rrfNdcg() > c.bm25Ndcg() + 1e-9).count();
            long rrfLosses = cases.stream().filter(c -> c.rrfNdcg() < c.bm25Ndcg() - 1e-9).count();
            System.out.println();
            System.out.println("--- Is the gain broad or concentrated? (RRF vs BM25, per query) ---");
            System.out.printf("  queries improved=%d  degraded=%d  unchanged=%d  (of %d)%n",
                    rrfWins, rrfLosses, evaluated - rrfWins - rrfLosses, evaluated);

            assertTrue(evaluated > 0);
        }
    }

    private static int intersection(List<Integer> a, List<Integer> b, int depth) {
        Set<Integer> top = new HashSet<>(a.subList(0, Math.min(depth, a.size())));
        int n = 0;
        for (int id : b.subList(0, Math.min(depth, b.size()))) {
            if (top.contains(id)) {
                n++;
            }
        }
        return n;
    }

    private static double jaccard(List<Integer> a, List<Integer> b, int depth) {
        Set<Integer> setA = new HashSet<>(a.subList(0, Math.min(depth, a.size())));
        Set<Integer> setB = new HashSet<>(b.subList(0, Math.min(depth, b.size())));
        if (setA.isEmpty() && setB.isEmpty()) {
            return 0;
        }
        Set<Integer> unionOf = new HashSet<>(setA);
        unionOf.addAll(setB);
        return (double) intersection(a, b, depth) / unionOf.size();
    }
}
