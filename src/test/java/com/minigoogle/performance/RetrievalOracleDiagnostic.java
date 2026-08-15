package com.minigoogle.performance;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.corpus.BeirCorpus;
import com.minigoogle.corpus.BeirIngestionPipeline;
import com.minigoogle.corpus.BeirQuery;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC ORACLE — separates a recall failure from a ranking failure.
 *
 * <p>Retrieval is exhaustive, so the candidate set for a query is exactly the
 * union of its terms' posting lists. This oracle reconstructs that union
 * independently of {@code SearchEngine} (by executing each analyzed term through
 * the planner) and compares it against the relevance judgments. That gives
 * <em>candidate recall</em>: the fraction of relevant documents that reached
 * scoring at all.</p>
 *
 * <p>The two failure modes it distinguishes:</p>
 * <ul>
 *   <li><b>relevant-but-never-retrieved</b> — the document is not in the
 *       candidate union. Analysis, expansion or candidate generation lost it,
 *       and no ranking change can recover it.</li>
 *   <li><b>relevant-but-ranked-too-low</b> — the document was scored but placed
 *       below the cutoff. This is a ranking problem, and tuning BM25 or the
 *       signal blend can address it.</li>
 * </ul>
 *
 * <p>Deciding between these before touching the ranking formula is the whole
 * point: they have disjoint fixes.</p>
 *
 * <pre>
 *   gradlew bench --tests "*RetrievalOracleDiagnostic" \
 *     -Dbeir.dir=data/beir/scifact -Dbeir.dataset=scifact
 * </pre>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class RetrievalOracleDiagnostic {

    private final Lexer lexer = new Lexer();
    private final QueryStopWordFilter stopWords = new QueryStopWordFilter();
    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final PorterStemmer stemmer = new PorterStemmer();

    /** Per-query diagnosis. */
    private record QueryDiagnosis(
            String qid,
            String text,
            int terms,
            int candidateCount,
            int relevantCount,
            int relevantInCandidates,
            double candidateRecall,
            double recallAt10,
            double recallAt100,
            double recallAtDeep,
            double ndcgAt10,
            double mrrAt10,
            int bestRelevantRank) {
    }

    /**
     * Analyzes a query exactly as {@code SearchEngine} does, then returns the
     * distinct stemmed terms it will actually look up.
     */
    private List<String> analyzedTerms(String query) {
        List<Token> tokens = stopWords.filter(lexer.tokenize(query));
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

    /** The exhaustive candidate union: every document containing any query term. */
    private Set<Integer> candidateUnion(QueryPlanner planner, List<String> terms) {
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
    void diagnoseRecallVersusRanking() throws IOException {
        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "scifact");
        int deepK = Integer.parseInt(System.getProperty("beir.deepK", "1000"));
        Path out = Path.of(System.getProperty("beir.out", "build/beir-oracle/" + dataset));
        Files.createDirectories(out);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, 0,
                out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.topK", String.valueOf(deepK));
        props.put("search.topK", String.valueOf(deepK));
        props.put("ranking.diversify.enabled", "false");
        // Explicit so the rerank stage is a controlled variable rather than an
        // implicit consequence of the semantic switch.
        String rerank = System.getProperty("beir.rerank", "false");
        props.put("ranking.rerank.enabled", rerank);

        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), out.resolve("index"));
        SearchEngine engine = build.engine();
        QueryPlanner planner = build.planner();

        System.out.println("=== Oracle diagnosis: " + dataset + " (" + corpus.docs().size()
                + " docs, deepK=" + deepK + ", lexical only, rerank=" + rerank + ") ===");

        List<QueryDiagnosis> diagnoses = new ArrayList<>();
        for (BeirQuery q : corpus.queries()) {
            Map<Integer, Integer> rel = qrels.getOrDefault(q.id(), Map.of());
            if (rel.isEmpty()) {
                continue;
            }
            Set<Integer> relevant = new HashSet<>();
            for (Map.Entry<Integer, Integer> e : rel.entrySet()) {
                if (e.getValue() > 0) {
                    relevant.add(e.getKey());
                }
            }
            if (relevant.isEmpty()) {
                continue;
            }

            List<String> terms = analyzedTerms(q.text());
            Set<Integer> candidates = candidateUnion(planner, terms);

            int relevantInCandidates = 0;
            for (int docId : relevant) {
                if (candidates.contains(docId)) {
                    relevantInCandidates++;
                }
            }

            List<Integer> ranked = engine.retrieveCandidates(q.text(), deepK).ranked()
                    .stream().map(RankedDocument::documentId).toList();

            int bestRank = -1;
            for (int i = 0; i < ranked.size(); i++) {
                if (relevant.contains(ranked.get(i))) {
                    bestRank = i + 1;
                    break;
                }
            }

            diagnoses.add(new QueryDiagnosis(
                    q.id(), q.text(), terms.size(),
                    candidates.size(), relevant.size(), relevantInCandidates,
                    relevant.isEmpty() ? 0 : (double) relevantInCandidates / relevant.size(),
                    RankingMetrics.recallAtK(ranked, rel, 10),
                    RankingMetrics.recallAtK(ranked, rel, 100),
                    RankingMetrics.recallAtK(ranked, rel, deepK),
                    RankingMetrics.ndcgAt(ranked, rel, 10),
                    RankingMetrics.mrrAtK(ranked, rel, 10),
                    bestRank));
        }

        // ── Aggregate ──
        double avgCandidateRecall = diagnoses.stream().mapToDouble(QueryDiagnosis::candidateRecall).average().orElse(0);
        double avgR10 = diagnoses.stream().mapToDouble(QueryDiagnosis::recallAt10).average().orElse(0);
        double avgR100 = diagnoses.stream().mapToDouble(QueryDiagnosis::recallAt100).average().orElse(0);
        double avgRDeep = diagnoses.stream().mapToDouble(QueryDiagnosis::recallAtDeep).average().orElse(0);
        double avgNdcg = diagnoses.stream().mapToDouble(QueryDiagnosis::ndcgAt10).average().orElse(0);
        double avgMrr = diagnoses.stream().mapToDouble(QueryDiagnosis::mrrAt10).average().orElse(0);
        double avgCandidates = diagnoses.stream().mapToInt(QueryDiagnosis::candidateCount).average().orElse(0);

        System.out.println();
        System.out.printf("queries evaluated        : %d%n", diagnoses.size());
        System.out.printf("mean candidate union     : %.0f docs (%.1f%% of corpus)%n",
                avgCandidates, 100.0 * avgCandidates / corpus.docs().size());
        System.out.printf("CANDIDATE RECALL         : %.4f   <- relevant docs that reached scoring%n", avgCandidateRecall);
        System.out.printf("Recall@10                : %.4f%n", avgR10);
        System.out.printf("Recall@100               : %.4f%n", avgR100);
        System.out.printf("Recall@%-18d: %.4f%n", deepK, avgRDeep);
        System.out.printf("NDCG@10                  : %.4f%n", avgNdcg);
        System.out.printf("MRR@10                   : %.4f%n", avgMrr);

        System.out.println();
        System.out.println("--- Diagnosis ---");
        double lostToRanking = avgCandidateRecall - avgR100;
        System.out.printf("  reached scoring but not returned in top-100 : %.4f of relevant%n", lostToRanking);
        System.out.printf("  never reached scoring at all                : %.4f of relevant%n",
                1.0 - avgCandidateRecall);
        if (avgCandidateRecall > 0.9) {
            System.out.println("  => candidate generation is NOT the bottleneck; this is a RANKING problem");
        } else if (avgCandidateRecall < 0.6) {
            System.out.println("  => relevant documents never reach scoring; this is a RECALL problem");
        } else {
            System.out.println("  => mixed: both candidate generation and ranking contribute");
        }

        // ── Worst queries by NDCG@10, for concrete debugging ──
        System.out.println();
        System.out.println("--- 10 worst queries by NDCG@10 ---");
        System.out.println("qid   terms cand    rel  relInCand candRecall ndcg@10 bestRank query");
        diagnoses.stream()
                .sorted(Comparator.comparingDouble(QueryDiagnosis::ndcgAt10))
                .limit(10)
                .forEach(d -> System.out.printf("%-6s%5d %7d %5d %9d %10.3f %7.4f %8s %s%n",
                        d.qid(), d.terms(), d.candidateCount(), d.relevantCount(),
                        d.relevantInCandidates(), d.candidateRecall(), d.ndcgAt10(),
                        d.bestRelevantRank() < 0 ? "none" : String.valueOf(d.bestRelevantRank()),
                        d.text().length() > 44 ? d.text().substring(0, 44) : d.text()));

        assertTrue(!diagnoses.isEmpty(), "the oracle must diagnose at least one query");
    }
}
