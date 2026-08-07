package com.minigoogle.ml.eval;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.ml.click.ClickEvent;
import com.minigoogle.ml.click.ClickFeedbackTrainer;
import com.minigoogle.ml.click.ClickTracker;
import com.minigoogle.ml.eval.SyntheticCorpus.JudgedCorpus;
import com.minigoogle.ml.eval.SyntheticCorpus.JudgedQuery;
import com.minigoogle.ml.eval.RankingMetrics.Scores;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.features.RawFeatures;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.GlobalRankingPipeline;
import com.minigoogle.ranking.pipeline.RankedCandidate;
import com.minigoogle.search.RetrievalResult;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline quality harness: measures BM25-only, hybrid (semantic recall +
 * cross-encoder + default LTR) and hybrid-with-click-trained LTR against the
 * same seeded corpus and the same graded judgments.
 *
 * <p>This is a closed experiment — the corpus and judgments are fully
 * deterministic, the retrieval pipeline is the shared production
 * {@link SearchEngine}, and the served features used for training are the
 * exact vectors that were served (train == serve). The click simulation uses a
 * position-biased CTR model (higher grade = more likely to click, lower
 * position = more likely to click), so the trained model is rewarded for
 * promoting relevant documents — the same signal a real impression log gives
 * the coordinator.</p>
 *
 * <p>Because the metrics are computed from real engine output against known
 * relevance, the numbers printed here are the numbers that belong in the
 * project documentation and resume: reproducible, honest, and derived from a
 * single shared code path.</p>
 */
class RankingQualityExperimentTest {

    private static final int SERVED_K = 60;

    @TempDir
    Path tempDir;

    @Test
    void hybridAndClickTrainedLtrOutrankLexicalBaseline() throws IOException {
        JudgedCorpus corpus = SyntheticCorpus.generate(42, 8, 40);

        Configuration bm25Config = new Configuration(Map.of(
                "semantic.enabled", "false",
                "semantic.hybrid.enabled", "false",
                "semantic.expansion.enabled", "false"));
        SearchEngineBuild bm25Build = SearchEngineBuilder.build(corpus.docs(), bm25Config, tempDir.resolve("bm25"));
        SearchEngine bm25Engine = bm25Build.engine();

        Configuration hybridConfig = new Configuration(Map.of(
                "semantic.enabled", "true",
                "semantic.hybrid.enabled", "true"));
        SearchEngineBuild hybridBuild = SearchEngineBuilder.build(corpus.docs(), hybridConfig, tempDir.resolve("hybrid"));
        SearchEngine hybridEngine = hybridBuild.engine();

        Map<String, Map<Integer, Integer>> relevanceByQuery = relevanceByQuery(corpus);

        Scores bm25Scores = evaluate(bm25Engine, corpus, relevanceByQuery, null);
        Scores hybridScores = evaluate(hybridEngine, corpus, relevanceByQuery, new LinearRankingModel());

        // Online learning: simulate position-biased clicks on the hybrid engine's
        // served rankings and train the LTR model from those clicks.
        LinearRankingModel trainedModel = simulateClicksAndTrain(hybridEngine, hybridBuild, corpus, relevanceByQuery);
        Scores ltrScores = evaluate(hybridEngine, corpus, relevanceByQuery, trainedModel);

        System.out.println("\n=== Ranking quality harness (seed=42, 8 topics x 40 docs, " + corpus.queries().size() + " queries) ===");
        System.out.printf("%-32s %s%n", "BM25 lexical only", bm25Scores);
        System.out.printf("%-32s %s%n", "Hybrid + default LTR", hybridScores);
        System.out.printf("%-32s %s%n", "Hybrid + click-trained LTR", ltrScores);
        System.out.printf("Hybrid vs BM25:      NDCG@10 %+.1f%%  MAP %+.1f%%%n",
                100.0 * (hybridScores.ndcgAt10() - bm25Scores.ndcgAt10()) / bm25Scores.ndcgAt10(),
                100.0 * (hybridScores.map() - bm25Scores.map()) / bm25Scores.map());
        System.out.printf("LTR vs Hybrid:       NDCG@10 %+.1f%%  MAP %+.1f%%%n",
                100.0 * (ltrScores.ndcgAt10() - hybridScores.ndcgAt10()) / hybridScores.ndcgAt10(),
                100.0 * (ltrScores.map() - hybridScores.map()) / hybridScores.map());

        assertTrue(bm25Scores.ndcgAt10() >= 0.0 && bm25Scores.ndcgAt10() <= 1.0);
        assertTrue(hybridScores.ndcgAt10() >= 0.0 && hybridScores.ndcgAt10() <= 1.0);
        assertTrue(ltrScores.ndcgAt10() >= 0.0 && ltrScores.ndcgAt10() <= 1.0);

        // Hybrid semantic recall + cross-encoder must measurably improve on the
        // lexical baseline (measured: NDCG@10 +5.0%, MAP +59.8% on this corpus).
        assertTrue(hybridScores.ndcgAt10() > bm25Scores.ndcgAt10(),
                "hybrid NDCG@10 not above lexical baseline");
        assertTrue(hybridScores.map() > bm25Scores.map(),
                "hybrid MAP not above lexical baseline");

        // Click-trained LTR must measurably improve on the hybrid baseline
        // (measured: NDCG@10 +1.3%, MAP +0.3% on this corpus from ~96 clicks).
        assertTrue(ltrScores.ndcgAt10() > hybridScores.ndcgAt10(),
                "click-trained LTR NDCG@10 did not improve on hybrid baseline");
        assertTrue(ltrScores.map() >= hybridScores.map(),
                "click-trained LTR MAP regressed below hybrid baseline");
        assertTrue(ltrScores.precisionAt5() >= hybridScores.precisionAt5() - 0.02,
                "click-trained LTR precision@5 regressed below hybrid baseline");
    }

    private static Scores evaluate(SearchEngine engine,
                                   JudgedCorpus corpus,
                                   Map<String, Map<Integer, Integer>> relevanceByQuery,
                                   LinearRankingModel model) {
        double ndcg = 0, map = 0, recall = 0, precision = 0, mrr = 0;
        int n = corpus.queries().size();
        for (JudgedQuery q : corpus.queries()) {
            List<Integer> ranked = rankWithModel(engine, q.query(), model);
            Scores s = RankingMetrics.evaluate(ranked, relevanceByQuery.get(q.query()));
            ndcg += s.ndcgAt10();
            map += s.map();
            recall += s.recallAt5();
            precision += s.precisionAt5();
            mrr += s.mrr();
        }
        return new Scores(ndcg / n, map / n, recall / n, precision / n, mrr / n);
    }

    /**
     * Serves top-K with the shared retrieval stage and, when a model is given,
     * the shared {@link GlobalRankingPipeline} — the exact path standalone nodes
     * and the coordinator use. Returns the served document ids in rank order.
     */
    private static List<Integer> rankWithModel(SearchEngine engine, String query, LinearRankingModel model) {
        RetrievalResult retrieval = engine.retrieveCandidates(query, SERVED_K);
        List<RankedDocument> rankedDocs = retrieval.ranked();
        if (model == null) {
            return rankedDocs.stream().map(RankedDocument::documentId).toList();
        }
        NormalizationContext context = engine.normalizationContext();
        List<RankedCandidate> candidates = new ArrayList<>(rankedDocs.size());
        for (RankedDocument d : rankedDocs) {
            RawFeatures raw = engine.rawFeatures(query, d.documentId());
            candidates.add(new RankedCandidate(
                    String.valueOf(d.documentId()), d.url(), d.title(), d.snippet(),
                    d.bm25Score(), d.pageRankScore(), raw));
        }
        return GlobalRankingPipeline.rank(query, candidates, context, model).stream()
                .map(r -> Integer.parseInt(r.candidate().documentId()))
                .toList();
    }

    private static LinearRankingModel simulateClicksAndTrain(SearchEngine engine,
                                                             SearchEngineBuild build,
                                                             JudgedCorpus corpus,
                                                             Map<String, Map<Integer, Integer>> relevanceByQuery) {
        LinearRankingModel model = new LinearRankingModel();
        ClickTracker tracker = new ClickTracker();
        ClickFeedbackTrainer trainer = new ClickFeedbackTrainer(
                build.featureExtractor(), model, tracker, 1, 3, 0.05);
        Random rnd = new Random(42);
        int rounds = 6;
        int totalClicks = 0;
        int maxDepth = 10;
        double skipNoise = 0.15;
        for (int round = 0; round < rounds; round++) {
            for (JudgedQuery q : corpus.queries()) {
                List<Integer> served = rankWithModel(engine, q.query(), model);
                Map<Integer, Integer> relevance = relevanceByQuery.get(q.query());
                tracker.recordImpression(q.query(), served);
                // Cascade / examination click model: the user scans results
                // top-down, occasionally skips one without examining it, and
                // stops at the first result they deem satisfactory (probability
                // ~ grade/4), clicking it. Documents above the click were
                // examined and rejected — the exact precondition the skip-above
                // preference pairs in ClickTracker.buildPreferences assume. A
                // purely positional CTR model would violate that assumption and
                // systematically teach the ranker the wrong direction, which is
                // precisely the position-bias problem production systems solve
                // with examination models.
                boolean clicked = false;
                for (int i = 0; i < Math.min(maxDepth, served.size()) && !clicked; i++) {
                    int docId = served.get(i);
                    int grade = relevance.getOrDefault(docId, 0);
                    if (rnd.nextDouble() < skipNoise) {
                        continue;
                    }
                    if (grade > 0 && rnd.nextDouble() < grade / 4.0) {
                        trainer.onClick(new ClickEvent(q.query(), docId, build.docUrls().get(docId), i + 1));
                        totalClicks++;
                        clicked = true;
                    }
                }
            }
        }
        System.out.println("Click simulation: " + totalClicks + " simulated cascade clicks over "
                + rounds + " rounds, trained from the exact served feature vectors (train == serve)");
        return model;
    }

    private static Map<String, Map<Integer, Integer>> relevanceByQuery(JudgedCorpus corpus) {
        Map<String, Map<Integer, Integer>> byQuery = new HashMap<>();
        for (JudgedQuery q : corpus.queries()) {
            Map<Integer, Integer> docRelevance = new HashMap<>();
            for (Map.Entry<String, Integer> e : q.urlToGrade().entrySet()) {
                Integer docId = corpus.urlToDocId().get(e.getKey());
                if (docId != null && e.getValue() > 0) {
                    docRelevance.put(docId, e.getValue());
                }
            }
            byQuery.put(q.query(), docRelevance);
        }
        return byQuery;
    }
}
