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
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC — does semantic retrieval recover what lexical retrieval misses?
 *
 * <p>This is the central question of the semantic mission, and it is not
 * answerable from aggregate NDCG. For every relevant document it determines
 * whether the document is reachable lexically, semantically, both, or neither,
 * and reports semantic candidate recall at increasing depth.</p>
 *
 * <p>The semantic side is built here rather than taken from
 * {@code SearchEngineBuild} (which does not expose the vector index), using the
 * same construction {@code SearchEngineBuilder} uses — {@code title + " " + text}
 * embedded by {@link EmbeddingGenerator} into a {@link VectorIndex}. That makes
 * it an independent oracle rather than a re-read of production state.</p>
 *
 * <pre>
 *   gradlew bench --tests "*SemanticRetrievalDiagnostic" \
 *     -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class SemanticRetrievalDiagnostic {

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

    @Test
    void doesSemanticRetrievalRecoverLexicalMisses() throws IOException {
        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "scifact");
        int dim = Integer.parseInt(System.getProperty("beir.dim", "128"));
        Path out = Path.of(System.getProperty("beir.out", "build/beir-semantic/" + dataset));
        Files.createDirectories(out);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, 0, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");   // lexical side only; semantic built below
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.rerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.topK", "1000");
        props.put("search.topK", "1000");

        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), out.resolve("index"));
        QueryPlanner planner = build.planner();

        // ── Independent semantic index, built exactly as SearchEngineBuilder does ──
        long embedStart = System.nanoTime();
        EmbeddingGenerator embedder = new EmbeddingGenerator(dim);
        VectorIndex vectors = new VectorIndex(dim, VectorIndex.VectorMode.EXACT);
        List<ParsedDocument> docs = corpus.docs();
        for (int i = 0; i < docs.size(); i++) {
            ParsedDocument doc = docs.get(i);
            vectors.add(i + 1, embedder.embed(doc.title() + " " + doc.text()), doc.title());
        }
        double embedSeconds = (System.nanoTime() - embedStart) / 1e9;

        System.out.println("=== Semantic diagnosis: " + dataset + " (" + docs.size()
                + " docs, dim=" + dim + ", exact search) ===");
        System.out.printf("embedding + index build: %.1fs for %d docs (%.0f docs/s)%n",
                embedSeconds, docs.size(), docs.size() / embedSeconds);

        int[] depths = {10, 50, 100, 500, 1000};
        Map<Integer, Double> semanticRecallAt = new LinkedHashMap<>();
        for (int k : depths) {
            semanticRecallAt.put(k, 0.0);
        }
        double lexicalCandRecall = 0;
        long both = 0, lexicalOnly = 0, semanticOnly = 0, neither = 0;
        double semNdcg = 0, semMrr = 0;
        List<Long> semanticLatencies = new ArrayList<>();
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

            List<String> terms = analyzedQueryTerms(q.text());
            Set<Integer> lexical = lexicalCandidates(planner, terms);

            long t0 = System.nanoTime();
            List<VectorIndex.VectorResult> semanticTop = vectors.search(embedder.embed(q.text()), 1000);
            semanticLatencies.add((System.nanoTime() - t0) / 1_000_000);

            List<Integer> semanticIds = semanticTop.stream().map(VectorIndex.VectorResult::id).toList();
            Set<Integer> semanticSet = new HashSet<>(semanticIds);

            for (int k : depths) {
                Set<Integer> topK = new HashSet<>(semanticIds.subList(0, Math.min(k, semanticIds.size())));
                long hit = relevant.stream().filter(topK::contains).count();
                semanticRecallAt.merge(k, (double) hit / relevant.size(), Double::sum);
            }

            long inLex = relevant.stream().filter(lexical::contains).count();
            lexicalCandRecall += (double) inLex / relevant.size();

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
        }

        System.out.println();
        System.out.printf("queries evaluated: %d%n", evaluated);
        System.out.printf("lexical candidate recall : %.4f%n", lexicalCandRecall / evaluated);
        System.out.println("semantic candidate recall by depth:");
        for (int k : depths) {
            System.out.printf("   K=%-5d %.4f%n", k, semanticRecallAt.get(k) / evaluated);
        }
        System.out.printf("semantic-only ranking: NDCG@10=%.4f  MRR@10=%.4f%n",
                semNdcg / evaluated, semMrr / evaluated);

        long totalRel = both + lexicalOnly + semanticOnly + neither;
        System.out.println();
        System.out.println("=== Reachability of relevant documents (semantic depth 1000) ===");
        System.out.printf("  BOTH          %7d  %5.1f%%%n", both, pct(both, totalRel));
        System.out.printf("  LEXICAL ONLY  %7d  %5.1f%%%n", lexicalOnly, pct(lexicalOnly, totalRel));
        System.out.printf("  SEMANTIC ONLY %7d  %5.1f%%   <- documents semantics adds%n",
                semanticOnly, pct(semanticOnly, totalRel));
        System.out.printf("  NEITHER       %7d  %5.1f%%%n", neither, pct(neither, totalRel));

        long lexMisses = semanticOnly + neither;
        System.out.println();
        System.out.printf("Of %d relevant documents the lexical path misses, semantic retrieval "
                        + "recovers %d (%.1f%%).%n",
                lexMisses, semanticOnly, pct(semanticOnly, lexMisses));

        semanticLatencies.sort(Long::compare);
        System.out.printf("semantic search latency (exact, top-1000): p50=%dms p95=%dms%n",
                semanticLatencies.get(semanticLatencies.size() / 2),
                semanticLatencies.get((int) (semanticLatencies.size() * 0.95)));

        assertTrue(evaluated > 0);
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0 : 100.0 * n / total;
    }
}
