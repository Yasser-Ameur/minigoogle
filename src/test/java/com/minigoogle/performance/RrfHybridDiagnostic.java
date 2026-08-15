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
 * <p>The decisive comparison is <b>union vs RRF</b>: the union established that
 * semantic candidates reach the pool but never the top-100, because the lexical
 * ranker has no signal for a document with no query-term overlap. RRF is the
 * first mechanism that can order those candidates, so this measures whether the
 * documents semantic retrieval finds finally become useful.</p>
 *
 * <p>Vectors are cached under {@code models/vectors/} rather than {@code build/}
 * — they cost 3–25 minutes to compute and {@code gradlew clean} would otherwise
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

        System.out.println("=== BM25 / semantic / union / RRF: " + dataset + " ("
                + corpus.docs().size() + " docs"
                + (maxDocs > 0 ? " [CAPPED " + maxDocs + " - diagnostic subset]" : "")
                + ", deepK=" + deepK + ") ===");

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
            System.out.printf("vector store built in %.1fs -> %.1f MB%n",
                    (System.nanoTime() - t0) / 1e9, Files.size(vectorFile) / 1e6);
        } else {
            System.out.println("reusing vector store " + vectorFile);
        }

        try (SemanticRetriever semantic = SemanticRetriever.load(encoder, vectorFile)) {
            int[] rrfKs = {10, 20, 40, 60, 100};
            int[] semanticDepths = {100, 500, 1000};
            int canonicalK = ReciprocalRankFusion.DEFAULT_K;
            int canonicalDepth = 1000;

            double[] bm25 = new double[6];
            double[] sem = new double[6];
            double[] union = new double[6];
            Map<Integer, double[]> byRrfK = new HashMap<>();
            for (int k : rrfKs) {
                byRrfK.put(k, new double[6]);
            }
            Map<Integer, double[]> byDepth = new HashMap<>();
            for (int d : semanticDepths) {
                byDepth.put(d, new double[6]);
            }

            // Semantic-only relevant documents, tracked through each system.
            long semOnly = 0, unionTop10 = 0, unionTop100 = 0, unionTop1000 = 0;
            long rrfTop10 = 0, rrfTop100 = 0, rrfTop1000 = 0;
            // Ranking overlap between the two systems.
            double overlap10 = 0, overlap50 = 0, overlap100 = 0;
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

                List<Integer> lexicalIds = engine.retrieveCandidates(q.text(), deepK).ranked()
                        .stream().map(RankedDocument::documentId).toList();
                Set<Integer> lexicalSet = new HashSet<>(lexicalIds);

                List<Integer> semIds = semantic.retrieve(q.text(), Math.max(deepK, 1000))
                        .stream().map(SemanticRetriever.Candidate::documentId).toList();

                accumulate(bm25, metrics(lexicalIds, rel, relevant, lexicalSet));
                accumulate(sem, metrics(semIds, rel, relevant, new HashSet<>(semIds)));

                // C: union at canonical depth, BM25 order then semantic-only appended.
                List<Integer> semCanonical = semIds.subList(0, Math.min(canonicalDepth, semIds.size()));
                LinkedHashSet<Integer> unionSet = new LinkedHashSet<>(lexicalIds);
                unionSet.addAll(semCanonical);
                List<Integer> unionIds = new ArrayList<>(unionSet);
                accumulate(union, metrics(unionIds, rel, relevant, new HashSet<>(unionIds)));

                // D: RRF, sweeping k at the canonical depth.
                List<Integer> canonicalRrf = null;
                for (int k : rrfKs) {
                    List<Integer> fused = new ReciprocalRankFusion(k)
                            .fuseToIds(lexicalIds, semCanonical);
                    accumulate(byRrfK.get(k), metrics(fused, rel, relevant, new HashSet<>(fused)));
                    if (k == canonicalK) {
                        canonicalRrf = fused;
                    }
                }

                // D: RRF, sweeping semantic depth at the canonical k.
                for (int d : semanticDepths) {
                    List<Integer> semD = semIds.subList(0, Math.min(d, semIds.size()));
                    List<Integer> fused = new ReciprocalRankFusion(canonicalK)
                            .fuseToIds(lexicalIds, semD);
                    accumulate(byDepth.get(d), metrics(fused, rel, relevant, new HashSet<>(fused)));
                }

                // Rank overlap between the two systems.
                overlap10 += intersection(lexicalIds, semIds, 10);
                overlap50 += intersection(lexicalIds, semIds, 50);
                overlap100 += intersection(lexicalIds, semIds, 100);

                // Where do semantic-only relevant documents land?
                Set<Integer> semCanonicalSet = new HashSet<>(semCanonical);
                for (int docId : relevant) {
                    if (lexicalSet.contains(docId) || !semCanonicalSet.contains(docId)) {
                        continue;
                    }
                    semOnly++;
                    int u = unionIds.indexOf(docId) + 1;
                    if (u > 0 && u <= 1000) {
                        unionTop1000++;
                    }
                    if (u > 0 && u <= 100) {
                        unionTop100++;
                    }
                    if (u > 0 && u <= 10) {
                        unionTop10++;
                    }
                    int r = canonicalRrf.indexOf(docId) + 1;
                    if (r > 0 && r <= 1000) {
                        rrfTop1000++;
                    }
                    if (r > 0 && r <= 100) {
                        rrfTop100++;
                    }
                    if (r > 0 && r <= 10) {
                        rrfTop10++;
                    }
                }
            }

            System.out.println();
            System.out.println("--- Four systems (identical ranking configuration) ---");
            print("A: BM25", bm25, evaluated);
            print("B: semantic", sem, evaluated);
            print("C: union (semK=1000)", union, evaluated);
            print("D: RRF k=60 semK=1000", byRrfK.get(canonicalK), evaluated);

            System.out.println();
            System.out.println("--- RRF k sweep (semK=1000) ---");
            for (int k : rrfKs) {
                print("RRF k=" + k, byRrfK.get(k), evaluated);
            }

            System.out.println();
            System.out.println("--- Semantic depth sweep (RRF k=60) ---");
            for (int d : semanticDepths) {
                print("RRF semK=" + d, byDepth.get(d), evaluated);
            }

            System.out.println();
            System.out.println("--- Semantic-only relevant documents (found by semantic, missed by BM25) ---");
            System.out.printf("  total                    : %d%n", semOnly);
            System.out.printf("  union  -> top10/100/1000 : %d / %d / %d%n",
                    unionTop10, unionTop100, unionTop1000);
            System.out.printf("  RRF    -> top10/100/1000 : %d / %d / %d%n",
                    rrfTop10, rrfTop100, rrfTop1000);

            System.out.println();
            System.out.println("--- Ranking overlap between BM25 and semantic ---");
            System.out.printf("  mean intersection@10=%.2f  @50=%.2f  @100=%.2f%n",
                    overlap10 / evaluated, overlap50 / evaluated, overlap100 / evaluated);

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
}
