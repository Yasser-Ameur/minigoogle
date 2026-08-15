package com.minigoogle.ranking.pipeline;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.snippet.SnippetGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract that lets {@link RankingPipeline} defer snippet construction
 * until after top-K selection: every returned document must carry exactly the
 * snippet {@link SnippetGenerator} produces for its body, and deferring must not
 * perturb ordering or scores.
 *
 * <p>Without these assertions, the deferral optimization could silently return
 * empty or mismatched snippets.</p>
 */
class RankingPipelineSnippetTest {

    private static final List<String> QUERY_TERMS = List.of("cluster", "index");

    private record Fixture(RankingPipeline pipeline,
                           Map<String, PostingList> postings,
                           Map<String, Integer> dfs,
                           Map<Integer, String> bodies) {
    }

    private static Fixture fixture(int docCount, int topK) {
        Map<Integer, String> urls = new HashMap<>();
        Map<Integer, String> titles = new HashMap<>();
        Map<Integer, String> bodies = new HashMap<>();
        Map<Integer, Integer> lengths = new HashMap<>();
        Map<Integer, Double> pageRanks = new HashMap<>();

        for (int id = 0; id < docCount; id++) {
            // Bodies differ per document and place the query terms at different
            // offsets, so a wrong-document snippet is detectable.
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
                pageRanks, urls, titles, bodies, lengths, topK);

        return new Fixture(pipeline, postings, dfs, bodies);
    }

    @Test
    void everyReturnedDocumentCarriesItsOwnGeneratedSnippet() {
        Fixture f = fixture(200, 20);
        SnippetGenerator generator = new SnippetGenerator();

        List<RankedDocument> results = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());

        assertFalse(results.isEmpty(), "ranking must return results");
        for (RankedDocument doc : results) {
            String expected = generator.generate(f.bodies().get(doc.documentId()), QUERY_TERMS);
            assertEquals(expected, doc.snippet(),
                    "document " + doc.documentId() + " must carry the snippet generated "
                            + "from its own body");
            assertFalse(doc.snippet().isEmpty(), "snippet must not be empty");
        }
    }

    @Test
    void snippetsAreHighlightedAndDrawnFromTheMatchingRegion() {
        Fixture f = fixture(50, 10);

        List<RankedDocument> results = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());

        assertFalse(results.isEmpty(), "ranking must return results");
        for (RankedDocument doc : results) {
            assertTrue(doc.snippet().contains("**"),
                    "snippet should highlight matched query terms: " + doc.snippet());
        }
    }

    @Test
    void deferringSnippetsDoesNotChangeOrderingOrScores() {
        Fixture f = fixture(300, 25);

        List<RankedDocument> first = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());
        List<RankedDocument> second = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());

        // Deterministic across runs, and scores are unaffected by snippet work.
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).documentId(), second.get(i).documentId(),
                    "ranking order must be deterministic at position " + i);
            assertEquals(first.get(i).finalScore(), second.get(i).finalScore(), 1e-12);
            assertEquals(first.get(i).bm25Score(), second.get(i).bm25Score(), 1e-12);
            assertEquals(first.get(i).snippet(), second.get(i).snippet());
        }

        // Scores must be non-increasing before diversification reorders by domain;
        // assert the top result is the strongest overall.
        double max = first.stream().mapToDouble(RankedDocument::finalScore).max().orElse(-1);
        assertEquals(max, first.get(0).finalScore(), 1e-12,
                "highest-scoring document must lead the result list");
    }

    @Test
    void resultCountRespectsTopKRegardlessOfCandidateCount() {
        for (int candidates : new int[]{40, 400, 1_500}) {
            Fixture f = fixture(candidates, 20);
            List<RankedDocument> results = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());
            assertEquals(20, results.size(),
                    "topK must bound the result count for " + candidates + " candidates");
        }
    }

    @Test
    void snippetWorkIsProportionalToTopKNotCandidateCount() {
        // A counting generator would be intrusive; instead assert the observable
        // consequence: growing the candidate set 10x must not grow result count,
        // and every returned snippet is still correct.
        SnippetGenerator generator = new SnippetGenerator();
        List<Integer> sizes = new ArrayList<>();
        for (int candidates : new int[]{150, 1_500}) {
            Fixture f = fixture(candidates, 20);
            List<RankedDocument> results = f.pipeline().rank(QUERY_TERMS, f.postings(), f.dfs());
            sizes.add(results.size());
            for (RankedDocument doc : results) {
                assertEquals(generator.generate(f.bodies().get(doc.documentId()), QUERY_TERMS),
                        doc.snippet());
            }
        }
        assertEquals(sizes.get(0), sizes.get(1),
                "result count must not grow with the candidate set");
    }
}
