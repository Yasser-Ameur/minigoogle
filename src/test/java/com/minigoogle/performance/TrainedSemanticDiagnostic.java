package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.ml.eval.RankingMetrics;
import com.minigoogle.query.QueryStopWordFilter;
import com.minigoogle.query.ast.WordNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.lexer.TokenType;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.semantic.encoder.SentenceEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decisive experiment: does a <em>trained</em> retrieval encoder recover the
 * relevant documents BM25 misses, where the feature-hash representation did not?
 *
 * <p>The hash baseline recovered 1.5% of lexical misses on scifact and 2.3% on
 * trec-covid. Those are the numbers this must beat to justify a 90 MB model and
 * an inference runtime.</p>
 *
 * <p>Document vectors are embedded once and persisted, so re-running the
 * evaluation does not re-embed the corpus and the benchmark measures search
 * rather than encoding. Search is exact (full scan) — the ground-truth oracle any
 * future ANN index must be validated against.</p>
 *
 * <pre>
 *   gradlew bench --tests "*TrainedSemanticDiagnostic" \
 *     -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class TrainedSemanticDiagnostic {

    private static final Path MODEL_DIR = Path.of("models", "all-MiniLM-L6-v2");

    private final Lexer lexer = new Lexer();
    private final QueryStopWordFilter queryStopWords = new QueryStopWordFilter();
    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final PorterStemmer stemmer = new PorterStemmer();

    private List<String> analyzedQueryTerms(String query) {
        List<Token> tokens = queryStopWords.filter(lexer.tokenize(query));
        List<String> terms = new ArrayList<>();
        for (Token t : tokens) {
            if (t.type() != TokenType.WORD) {
                continue;
            }
            String processed = stemmer.stem(caseFolder.fold(normalizer.normalize(t.value())));
            if (!processed.isEmpty() && !terms.contains(processed)) {
                terms.add(processed);
            }
        }
        return terms;
    }

    private Set<Integer> lexicalCandidates(QueryPlanner planner, List<String> terms) {
        Set<Integer> union = new HashSet<>();
        QueryPlanner scoped = planner.forQuery();
        for (String term : terms) {
            PostingList postings = scoped.execute(new WordNode(term));
            for (Posting p : postings.getPostings()) {
                union.add(p.getDocumentId());
            }
        }
        return union;
    }

    /** Embeds the corpus once and caches it; subsequent runs load from disk. */
    private float[][] documentVectors(SentenceEncoder encoder, List<ParsedDocument> docs, Path cache)
            throws IOException, ai.onnxruntime.OrtException {
        int dim = encoder.dimension();
        if (Files.isRegularFile(cache)) {
            try (DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(cache), 1 << 20))) {
                int count = in.readInt();
                int storedDim = in.readInt();
                if (count == docs.size() && storedDim == dim) {
                    float[][] vectors = new float[count + 1][];   // 1-based doc ids
                    for (int i = 1; i <= count; i++) {
                        float[] v = new float[dim];
                        for (int d = 0; d < dim; d++) {
                            v[d] = in.readFloat();
                        }
                        vectors[i] = v;
                    }
                    System.out.println("loaded cached document vectors from " + cache);
                    return vectors;
                }
            }
        }

        int batchSize = 32;
        float[][] vectors = new float[docs.size() + 1][];
        long start = System.nanoTime();
        for (int i = 0; i < docs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, docs.size());
            String[] batch = new String[end - i];
            for (int j = i; j < end; j++) {
                ParsedDocument d = docs.get(j);
                batch[j - i] = d.title() + " " + d.text();
            }
            float[][] encoded = encoder.encodeBatch(batch);
            for (int j = i; j < end; j++) {
                vectors[j + 1] = encoded[j - i];
            }
            if ((i / batchSize) % 200 == 0 && i > 0) {
                double elapsed = (System.nanoTime() - start) / 1e9;
                System.out.printf("  embedded %d/%d (%.0f docs/s)%n", i, docs.size(), i / elapsed);
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("embedded %d documents in %.1fs (%.0f docs/s)%n",
                docs.size(), seconds, docs.size() / seconds);

        Files.createDirectories(cache.getParent());
        try (DataOutputStream out = new DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(cache), 1 << 20))) {
            out.writeInt(docs.size());
            out.writeInt(dim);
            for (int i = 1; i <= docs.size(); i++) {
                for (float v : vectors[i]) {
                    out.writeFloat(v);
                }
            }
        }
        System.out.printf("persisted vectors: %.1f MB%n", Files.size(cache) / 1e6);
        return vectors;
    }

    /** Exact top-K by cosine — the ground-truth oracle. */
    private List<Integer> exactSearch(float[][] vectors, float[] query, int k) {
        PriorityQueue<int[]> heap = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1] / 1e6));
        PriorityQueue<double[]> best = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
        for (int id = 1; id < vectors.length; id++) {
            if (vectors[id] == null) {
                continue;
            }
            double score = SentenceEncoder.similarity(query, vectors[id]);
            if (best.size() < k) {
                best.offer(new double[]{id, score});
            } else if (score > best.peek()[1]) {
                best.poll();
                best.offer(new double[]{id, score});
            }
        }
        List<double[]> sorted = new ArrayList<>(best);
        sorted.sort((a, b) -> Double.compare(b[1], a[1]));
        List<Integer> ids = new ArrayList<>(sorted.size());
        for (double[] e : sorted) {
            ids.add((int) e[0]);
        }
        return ids;
    }

    @Test
    void doesATrainedEncoderRecoverLexicalMisses() throws Exception {
        assertTrue(SentenceEncoder.isAvailable(MODEL_DIR),
                "encoder model missing at " + MODEL_DIR.toAbsolutePath());

        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "scifact");
        int threads = Integer.parseInt(System.getProperty("beir.threads", "4"));
        Path out = Path.of(System.getProperty("beir.out", "build/beir-trained/" + dataset));
        Files.createDirectories(out);

        // maxDocs caps the corpus. Both the lexical and semantic sides below are
        // built from the SAME capped corpus and the judgments are resolved against
        // it, so the comparison stays one-variable - but the absolute numbers are
        // not comparable to a full-corpus run and are labelled as such.
        int maxDocs = Integer.parseInt(System.getProperty("beir.maxDocs", "0"));
        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, maxDocs, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.rerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.topK", "1000");
        props.put("search.topK", "1000");

        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), out.resolve("index"));
        QueryPlanner planner = build.planner();

        System.out.println("=== Trained-encoder diagnosis: " + dataset
                + " (" + corpus.docs().size() + " docs"
                + (maxDocs > 0 ? " [CAPPED at " + maxDocs + "]" : "")
                + ", all-MiniLM-L6-v2, exact search) ===");

        try (SentenceEncoder encoder = SentenceEncoder.load(
                MODEL_DIR, SentenceEncoder.DEFAULT_MAX_TOKENS, SentenceEncoder.MINILM_DIMENSION, threads)) {

            float[][] vectors = documentVectors(encoder, corpus.docs(),
                    out.resolve("vectors-minilm-" + corpus.docs().size() + ".bin"));

            int[] depths = {10, 50, 100, 500, 1000};
            Map<Integer, Double> recallAt = new LinkedHashMap<>();
            for (int k : depths) {
                recallAt.put(k, 0.0);
            }
            long both = 0, lexicalOnly = 0, semanticOnly = 0, neither = 0;
            double semNdcg = 0, semMrr = 0, semR10 = 0, semR100 = 0;
            double lexCandRecall = 0, unionCandRecall = 0;
            List<Long> latencies = new ArrayList<>();
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

                Set<Integer> lexical = lexicalCandidates(planner, analyzedQueryTerms(q.text()));

                long t0 = System.nanoTime();
                float[] qv = encoder.encode(q.text());
                List<Integer> semanticIds = exactSearch(vectors, qv, 1000);
                latencies.add((System.nanoTime() - t0) / 1_000_000);

                Set<Integer> semanticSet = new HashSet<>(semanticIds);
                for (int k : depths) {
                    Set<Integer> topK = new HashSet<>(semanticIds.subList(0, Math.min(k, semanticIds.size())));
                    long hit = relevant.stream().filter(topK::contains).count();
                    recallAt.merge(k, (double) hit / relevant.size(), Double::sum);
                }

                lexCandRecall += (double) relevant.stream().filter(lexical::contains).count() / relevant.size();
                Set<Integer> union = new HashSet<>(lexical);
                union.addAll(semanticSet);
                unionCandRecall += (double) relevant.stream().filter(union::contains).count() / relevant.size();

                for (int docId : relevant) {
                    boolean l = lexical.contains(docId);
                    boolean s = semanticSet.contains(docId);
                    if (l && s) {
                        both++;
                    } else if (l) {
                        lexicalOnly++;
                    } else if (s) {
                        semanticOnly++;
                    } else {
                        neither++;
                    }
                }

                semNdcg += RankingMetrics.ndcgAt(semanticIds, rel, 10);
                semMrr += RankingMetrics.mrrAtK(semanticIds, rel, 10);
                semR10 += RankingMetrics.recallAtK(semanticIds, rel, 10);
                semR100 += RankingMetrics.recallAtK(semanticIds, rel, 100);
            }

            System.out.println();
            System.out.printf("queries evaluated: %d%n", evaluated);
            System.out.println("semantic candidate recall by depth (trained encoder):");
            for (int k : depths) {
                System.out.printf("   K=%-5d %.4f%n", k, recallAt.get(k) / evaluated);
            }
            System.out.printf("semantic-only ranking: NDCG@10=%.4f MRR@10=%.4f R@10=%.4f R@100=%.4f%n",
                    semNdcg / evaluated, semMrr / evaluated, semR10 / evaluated, semR100 / evaluated);

            System.out.println();
            System.out.printf("lexical candidate recall        : %.4f%n", lexCandRecall / evaluated);
            System.out.printf("UNION candidate recall          : %.4f   <- lexical + semantic%n",
                    unionCandRecall / evaluated);

            long total = both + lexicalOnly + semanticOnly + neither;
            System.out.println();
            System.out.println("=== Reachability of relevant documents (semantic depth 1000) ===");
            System.out.printf("  BOTH          %7d  %5.1f%%%n", both, pct(both, total));
            System.out.printf("  LEXICAL ONLY  %7d  %5.1f%%%n", lexicalOnly, pct(lexicalOnly, total));
            System.out.printf("  SEMANTIC ONLY %7d  %5.1f%%   <- recovered by the trained encoder%n",
                    semanticOnly, pct(semanticOnly, total));
            System.out.printf("  NEITHER       %7d  %5.1f%%%n", neither, pct(neither, total));

            long lexMisses = semanticOnly + neither;
            System.out.printf("%nOf %d relevant documents lexical retrieval misses, the trained "
                            + "encoder recovers %d (%.1f%%).%n",
                    lexMisses, semanticOnly, pct(semanticOnly, lexMisses));

            latencies.sort(Long::compare);
            System.out.printf("query encode + exact scan: p50=%dms p95=%dms%n",
                    latencies.get(latencies.size() / 2),
                    latencies.get((int) (latencies.size() * 0.95)));

            assertTrue(evaluated > 0);
        }
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0 : 100.0 * n / total;
    }
}
