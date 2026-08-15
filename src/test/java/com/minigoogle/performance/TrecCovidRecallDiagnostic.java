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
import com.minigoogle.indexer.stopwords.StopWordFilter;
import com.minigoogle.indexer.tokenizer.Tokenizer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIAGNOSTIC — why do relevant documents disappear before ranking?
 *
 * <p>Candidate recall is measured strictly <em>before</em> scoring: the candidate
 * set is the union of the query terms' posting lists, reconstructed independently
 * of {@code SearchEngine}. For every relevant document missing from that union,
 * this walks backwards and asks what the document actually contains, which
 * classifies the loss:</p>
 *
 * <pre>
 *   TYPE_A  an analyzed query term IS in the document's analyzed tokens,
 *           yet the document is not in the union  -> planner or index defect
 *   TYPE_B  a query term appears in the raw text but not in the analyzed
 *           tokens                                -> normalization mismatch
 *   TYPE_E  no lexical overlap at all             -> semantic relevance,
 *                                                    unreachable by set algebra
 * </pre>
 *
 * <p>The distinction decides whether this is fixable by retrieval engineering at
 * all. TYPE_A is a bug, TYPE_B is an analysis change, TYPE_E is not a Boolean
 * problem and must not be attacked with one.</p>
 */
@EnabledIfSystemProperty(named = "beir.dir", matches = ".+")
class TrecCovidRecallDiagnostic {

    private final Lexer lexer = new Lexer();
    private final QueryStopWordFilter queryStopWords = new QueryStopWordFilter();
    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final PorterStemmer stemmer = new PorterStemmer();
    private final StopWordFilter indexStopWords = new StopWordFilter();
    private final Tokenizer tokenizer = new Tokenizer();

    private record QueryRow(String qid, String text, int terms, int candidateCount,
                            int relevantCount, int relevantInCandidates, double candidateRecall,
                            double recallAt100, double recallAt1000, double ndcgAt10) {
    }

    /** Query analysis, mirroring SearchEngine. */
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

    /** Document analysis, mirroring IndexBuilder exactly. */
    private Set<String> analyzedDocumentTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        for (String raw : tokenizer.tokenize(normalizer.normalize(text))) {
            String folded = caseFolder.fold(raw);
            if (indexStopWords.isStopWord(folded)) {
                continue;
            }
            String stemmed = stemmer.stem(folded);
            if (!stemmed.isEmpty()) {
                tokens.add(stemmed);
            }
        }
        return tokens;
    }

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
    void diagnoseCandidateRecallLoss() throws IOException {
        Path dir = Path.of(System.getProperty("beir.dir"));
        String dataset = System.getProperty("beir.dataset", "trec-covid");
        int deepK = Integer.parseInt(System.getProperty("beir.deepK", "1000"));
        Path out = Path.of(System.getProperty("beir.out", "build/beir-recall/" + dataset));
        Files.createDirectories(out);

        BeirCorpus corpus = BeirIngestionPipeline.load(dir, dataset, 0, out.resolve("work"), p -> { });
        Map<String, Map<Integer, Integer>> qrels = corpus.resolveQrels("test");

        Map<String, String> props = new HashMap<>();
        props.put("semantic.enabled", "false");
        props.put("semantic.hybrid.enabled", "false");
        props.put("semantic.expansion.enabled", "false");
        props.put("ranking.topK", String.valueOf(deepK));
        props.put("search.topK", String.valueOf(deepK));
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.rerank.enabled", "false");

        SearchEngineBuild build = SearchEngineBuilder.build(
                corpus.docs(), new Configuration(props), out.resolve("index"));
        SearchEngine engine = build.engine();
        QueryPlanner planner = build.planner();
        Map<Integer, String> docBodies = build.docBodies();
        Map<Integer, String> docTitles = build.docTitles();

        System.out.println("=== Candidate-recall diagnosis: " + dataset
                + " (" + corpus.docs().size() + " docs, deepK=" + deepK + ") ===");

        List<QueryRow> rows = new ArrayList<>();
        long typeA = 0, typeB = 0, typeE = 0, missedTotal = 0, titleOnly = 0;
        // Rank histogram over relevant documents.
        Map<String, Integer> histogram = new LinkedHashMap<>();
        for (String bucket : List.of("1-10", "11-50", "51-100", "101-200", "201-500",
                "501-1000", ">1000", "never retrieved")) {
            histogram.put(bucket, 0);
        }
        List<String> traces = new ArrayList<>();

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

            List<String> terms = analyzedQueryTerms(q.text());
            Set<Integer> candidates = candidateUnion(planner, terms);

            List<Integer> ranked = engine.retrieveCandidates(q.text(), deepK).ranked()
                    .stream().map(RankedDocument::documentId).toList();
            Map<Integer, Integer> rankOf = new HashMap<>();
            for (int i = 0; i < ranked.size(); i++) {
                rankOf.put(ranked.get(i), i + 1);
            }

            int inCandidates = 0;
            for (int docId : relevant) {
                if (candidates.contains(docId)) {
                    inCandidates++;
                }

                Integer r = rankOf.get(docId);
                String bucket;
                if (r == null) {
                    bucket = candidates.contains(docId) ? ">1000" : "never retrieved";
                } else if (r <= 10) {
                    bucket = "1-10";
                } else if (r <= 50) {
                    bucket = "11-50";
                } else if (r <= 100) {
                    bucket = "51-100";
                } else if (r <= 200) {
                    bucket = "101-200";
                } else if (r <= 500) {
                    bucket = "201-500";
                } else {
                    bucket = "501-1000";
                }
                histogram.merge(bucket, 1, Integer::sum);

                if (!candidates.contains(docId)) {
                    missedTotal++;
                    String title = docTitles.getOrDefault(docId, "");
                    String bodyOnly = docBodies.getOrDefault(docId, "");
                    String body = title + " " + bodyOnly;
                    Set<String> docTokens = analyzedDocumentTokens(body);
                    Set<String> bodyTokens = analyzedDocumentTokens(bodyOnly);

                    boolean analyzedOverlap = terms.stream().anyMatch(docTokens::contains);
                    if (analyzedOverlap) {
                        typeA++;
                        boolean inBody = terms.stream().anyMatch(bodyTokens::contains);
                        if (!inBody) {
                            titleOnly++;
                        }
                        if (traces.size() < 8) {
                            String shared = terms.stream().filter(docTokens::contains).toList().toString();
                            traces.add("TYPE_A q=" + q.id() + " doc=" + docId
                                    + " shares analyzed terms " + shared
                                    + (inBody ? " (present in body)" : " (TITLE ONLY - title is not indexed)"));
                        }
                    } else {
                        String rawLower = body.toLowerCase(Locale.ROOT);
                        boolean rawOverlap = terms.stream().anyMatch(rawLower::contains);
                        if (rawOverlap) {
                            typeB++;
                            if (traces.size() < 8) {
                                String shared = terms.stream().filter(rawLower::contains).toList().toString();
                                traces.add("TYPE_B q=" + q.id() + " doc=" + docId
                                        + " raw text contains " + shared
                                        + " but analysis did not produce those tokens");
                            }
                        } else {
                            typeE++;
                            if (traces.size() < 8 && typeE < 3) {
                                traces.add("TYPE_E q=" + q.id() + " doc=" + docId
                                        + " has no lexical overlap with query terms " + terms);
                            }
                        }
                    }
                }
            }

            rows.add(new QueryRow(q.id(), q.text(), terms.size(), candidates.size(),
                    relevant.size(), inCandidates, (double) inCandidates / relevant.size(),
                    RankingMetrics.recallAtK(ranked, rel, 100),
                    RankingMetrics.recallAtK(ranked, rel, deepK),
                    RankingMetrics.ndcgAt(ranked, rel, 10)));
        }

        // ── Per-query table ──
        System.out.println();
        System.out.println("qid  terms  candidates   rel  inCand  candRecall  R@100  R@1000  NDCG@10  query");
        rows.stream()
                .sorted(Comparator.comparingDouble(QueryRow::candidateRecall))
                .forEach(r -> System.out.printf("%-5s%5d %11d %5d %7d %11.4f %6.4f %7.4f %8.4f  %s%n",
                        r.qid(), r.terms(), r.candidateCount(), r.relevantCount(),
                        r.relevantInCandidates(), r.candidateRecall(),
                        r.recallAt100(), r.recallAt1000(), r.ndcgAt10(),
                        r.text().length() > 38 ? r.text().substring(0, 38) : r.text()));

        // ── Failure breakdown ──
        System.out.println();
        System.out.println("=== Missed-relevant breakdown (" + missedTotal + " missed judgments) ===");
        System.out.printf("  TYPE_A analyzed term present in document           : %6d  %5.1f%%%n",
                typeA, pct(typeA, missedTotal));
        System.out.printf("     of which the term appears ONLY in the title     : %6d  %5.1f%% of all missed%n",
                titleOnly, pct(titleOnly, missedTotal));
        System.out.printf("  TYPE_B normalization mismatch (raw text match)      : %6d  %5.1f%%%n",
                typeB, pct(typeB, missedTotal));
        System.out.printf("  TYPE_E no lexical overlap (semantic relevance)      : %6d  %5.1f%%%n",
                typeE, pct(typeE, missedTotal));

        System.out.println();
        System.out.println("--- representative traces ---");
        traces.forEach(t -> System.out.println("  " + t));

        // ── Rank histogram ──
        int totalJudged = histogram.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println();
        System.out.println("=== Where relevant documents land (" + totalJudged + " judgments) ===");
        histogram.forEach((bucket, count) ->
                System.out.printf("  %-16s %6d  %5.1f%%%n", bucket, count, pct(count, totalJudged)));

        double meanCandRecall = rows.stream().mapToDouble(QueryRow::candidateRecall).average().orElse(0);
        System.out.printf("%nmean candidate recall = %.4f%n", meanCandRecall);

        assertTrue(!rows.isEmpty(), "diagnostic must evaluate at least one query");
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0 : 100.0 * n / total;
    }
}
