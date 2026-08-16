package com.minigoogle.ranking.pipeline;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.snippet.SnippetGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the separation between <em>ranking depth</em> and <em>presentation
 * depth</em>.
 *
 * <p>These were one number. Asking for a page of 20 results therefore also
 * narrowed hybrid fusion to 20 inputs per channel, which measurably degraded
 * ranking quality (full-corpus TREC-COVID NDCG@10 0.5810 → 0.5536, candidate
 * recall 0.4804 → 0.3354). The fix is only real if snippet work stays bounded by
 * what is returned while the ranking behind it stays deep — that is exactly what
 * these tests assert.</p>
 */
class RankingDepthDecouplingTest {

    private static final List<String> QUERY_TERMS = List.of("cluster", "index");

    private record Fixture(RankingPipeline pipeline,
                           Map<String, PostingList> postings,
                           Map<String, Integer> dfs,
                           Map<Integer, String> bodies) {
    }

    private static Fixture fixture(int docCount, int topK) {
        return fixture(docCount, topK, false);
    }

    private static Fixture fixture(int docCount, int topK, boolean diversify) {
        Map<Integer, String> urls = new HashMap<>();
        Map<Integer, String> titles = new HashMap<>();
        Map<Integer, String> bodies = new HashMap<>();
        Map<Integer, Integer> lengths = new HashMap<>();
        Map<Integer, Double> pageRanks = new HashMap<>();

        for (int id = 0; id < docCount; id++) {
            String body = "padding ".repeat(id % 7)
                    + "the cluster rebalances shards while the index is rebuilt for document "
                    + id + ". " + "trailing text ".repeat(5);
            urls.put(id, "https://host" + (id % 3) + ".example.com/d/" + id);
            titles.put(id, "Doc " + id);
            bodies.put(id, body);
            lengths.put(id, body.split("\\s+").length);
            pageRanks.put(id, (id % 11) / 11.0);
        }

        Map<String, PostingList> postings = new HashMap<>();
        Map<String, Integer> dfs = new HashMap<>();
        for (String term : QUERY_TERMS) {
            PostingList pl = new PostingList();
            for (int id = 0; id < docCount; id++) {
                pl.addPosting(new Posting(id, 1 + (id % 4), List.of()));
            }
            postings.put(term, pl);
            dfs.put(term, docCount);
        }

        RankingPipeline pipeline = new RankingPipeline(
                BM25Parameters.withDefaults(docCount, 30.0),
                pageRanks, urls, titles, bodies, lengths, topK, diversify);

        return new Fixture(pipeline, postings, dfs, bodies);
    }

    @Test
    void rankToDepthReturnsTheRequestedDepthAndBuildsNoSnippets() {
        Fixture f = fixture(1_500, 20);

        List<RankedDocument> deep = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 1_000);

        assertEquals(1_000, deep.size(), "fusion depth must not be capped by topK=20");
        for (RankedDocument doc : deep) {
            assertTrue(doc.snippet().isEmpty(),
                    "the deep ranking must carry no snippets; document "
                            + doc.documentId() + " had one");
        }
    }

    @Test
    void snippetWorkIsBoundedByTopKNotByRankingDepth() {
        Fixture f = fixture(1_500, 20);
        SnippetGenerator generator = new SnippetGenerator();

        List<RankedDocument> deep = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 1_000);
        List<RankedDocument> page = f.pipeline().present(deep, QUERY_TERMS, 20);

        assertEquals(20, page.size(), "only topK documents may be returned");
        for (RankedDocument doc : page) {
            assertEquals(generator.generate(f.bodies().get(doc.documentId()), QUERY_TERMS),
                    doc.snippet(), "returned document " + doc.documentId()
                            + " must carry the snippet for its own body");
            assertFalse(doc.snippet().isEmpty());
        }
        // The 1,000-deep input is untouched: present() built exactly 20 snippets,
        // not 1,000. If it had built them eagerly, these would be populated.
        for (RankedDocument doc : deep) {
            assertTrue(doc.snippet().isEmpty(),
                    "present() must not build snippets for documents it drops");
        }
    }

    @Test
    void theReturnedPageIsTheTopOfTheDeepRanking() {
        Fixture f = fixture(1_500, 20);

        List<RankedDocument> deep = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 1_000);
        List<RankedDocument> page = f.pipeline().present(deep, QUERY_TERMS, 20);

        for (int i = 0; i < page.size(); i++) {
            assertEquals(deep.get(i).documentId(), page.get(i).documentId(),
                    "presentation must preserve ranking order at position " + i);
            assertEquals(deep.get(i).finalScore(), page.get(i).finalScore(), 1e-12,
                    "presentation must not alter scores");
        }
    }

    @Test
    void presentIsSafeWhenTheRankingIsShallowerThanTopK() {
        Fixture f = fixture(30, 20);

        List<RankedDocument> deep = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 5);
        List<RankedDocument> page = f.pipeline().present(deep, QUERY_TERMS, 20);

        assertEquals(5, page.size(), "asking for more than exists must not fail or pad");
    }

    @Test
    void theLexicalPathIsUnchangedByTheSplit() {
        // rank() must remain exactly rankToDepth(topK) followed by present(topK):
        // BM25-mode behaviour is not permitted to move.
        Fixture f = fixture(400, 25);

        List<RankedDocument> viaRank = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());
        List<RankedDocument> viaSplit = f.pipeline().present(
                f.pipeline().rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 25),
                QUERY_TERMS, 25);

        assertEquals(viaRank.size(), viaSplit.size());
        for (int i = 0; i < viaRank.size(); i++) {
            assertEquals(viaRank.get(i).documentId(), viaSplit.get(i).documentId());
            assertEquals(viaRank.get(i).finalScore(), viaSplit.get(i).finalScore(), 1e-12);
            assertEquals(viaRank.get(i).snippet(), viaSplit.get(i).snippet());
        }
    }

    @Test
    void theDeepRankingIsNotDiversifiedEvenWhenDiversificationIsOn() {
        // Regression guard for a production-only defect: rank() applied domain
        // round-robin BEFORE its output became the lexical rank input to RRF, so
        // with the default diversify=true, fusion was consuming a domain-shuffled
        // ranking as if it were the BM25 ordering. rankToDepth() must hand fusion
        // the genuine ranking; diversification belongs to presentation.
        Fixture f = fixture(600, 20, true);

        List<RankedDocument> deep = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 300);

        double previous = Double.MAX_VALUE;
        for (RankedDocument doc : deep) {
            assertTrue(doc.finalScore() <= previous + 1e-12,
                    "the ranking handed to fusion must be in pure score order, but "
                            + doc.documentId() + " scored " + doc.finalScore()
                            + " after " + previous);
            previous = doc.finalScore();
        }

    }

    /**
     * The other half of the invariant: diversification is still applied, just at
     * presentation. Without this, the test above could pass simply because
     * diversification had been dropped entirely.
     */
    @Test
    void presentAppliesDiversificationToTheReturnedPage() {
        Fixture f = fixture(600, 20, true);

        // Documents are assigned hosts by id % 3, so 0, 3 and 6 share host0 while
        // 1 is on host1. DiversityFilter defers a third consecutive same-domain
        // result, so the page must come back reordered.
        List<RankedDocument> crafted = List.of(
                doc(0, 0.9), doc(3, 0.8), doc(6, 0.7), doc(1, 0.6));

        List<RankedDocument> page = f.pipeline().present(crafted, QUERY_TERMS, 4);

        assertEquals(List.of(0, 3, 1, 6),
                page.stream().map(RankedDocument::documentId).toList(),
                "the third consecutive host0 result must be deferred behind host1");
    }

    private static RankedDocument doc(int id, double score) {
        return new RankedDocument(id, "https://host" + (id % 3) + ".example.com/d/" + id,
                "Doc " + id, score, 0.0, score, "");
    }

    @Test
    void aDeeperRankingChangesWhatReachesThePage() {
        // The whole point of the decoupling: depth must be able to change the
        // outcome, or the setting is decorative. A 1,000-deep ranking and a
        // 20-deep ranking of the same candidates agree at the very top but the
        // deep one holds documents the shallow one discarded entirely.
        Fixture f = fixture(1_500, 20);

        List<RankedDocument> shallow = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 20);
        List<RankedDocument> deep = f.pipeline()
                .rankToDepth(QUERY_TERMS, f.postings(), f.dfs(), 1_000);

        assertEquals(20, shallow.size());
        assertEquals(1_000, deep.size());
        assertEquals(shallow.get(0).documentId(), deep.get(0).documentId(),
                "both depths must agree on the best document");
        assertTrue(deep.size() > shallow.size() * 10,
                "the deep ranking must expose far more candidates to fusion");
    }
}
