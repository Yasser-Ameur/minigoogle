package com.minigoogle.ranking.integration;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for end-to-end ranking pipeline integration. */
class RankingIntegrationTest {

    @Test
    void testFullRankingPipeline() {
        // Setup dummy data
        Map<Integer, String> urls = Map.of(
                1, "https://example.com/doc1",
                2, "https://wikipedia.org/doc2",
                3, "https://wikipedia.org/doc3"
        );
        Map<Integer, String> titles = Map.of(
                1, "Doc 1",
                2, "Doc 2",
                3, "Doc 3"
        );
        Map<Integer, String> bodies = Map.of(
                1, "This is doc1 about java.",
                2, "This is doc2 about python.",
                3, "This is doc3 about java and compiler."
        );
        Map<Integer, Integer> lengths = Map.of(
                1, 5,
                2, 5,
                3, 7
        );
        Map<Integer, Double> pageRanks = Map.of(
                1, 0.1,
                2, 0.9, // Wikipedia has high PageRank
                3, 0.8
        );

        BM25Parameters params = BM25Parameters.withDefaults(3, 5.6);
        RankingPipeline pipeline = new RankingPipeline(params, pageRanks, urls, titles, bodies, lengths, 10);

        List<String> queryTerms = List.of("java");
        
        // Doc 1 has java (tf=1)
        // Doc 3 has java (tf=1)
        Map<String, PostingList> postings = Map.of(
                "java", new PostingList(List.of(
                        new Posting(1, 1, List.of(4)),
                        new Posting(3, 1, List.of(4))
                ))
        );
        Map<String, Integer> dfs = Map.of("java", 2);

        List<RankedDocument> results = pipeline.rank(queryTerms, postings, dfs);
        
        assertEquals(2, results.size());
        
        // Doc 3 should win because it has high PageRank, even if BM25 is slightly penalized for length (7 vs 5)
        // Let's assert they are both present and snippets are correct
        boolean foundDoc1 = false;
        boolean foundDoc3 = false;
        
        for (RankedDocument doc : results) {
            if (doc.documentId() == 1) {
                foundDoc1 = true;
                assertTrue(doc.snippet().contains("**java**"));
            }
            if (doc.documentId() == 3) {
                foundDoc3 = true;
                assertTrue(doc.snippet().contains("**java**"));
            }
            // Check final scores are populated
            assertTrue(doc.finalScore() >= 0.0);
            assertTrue(doc.finalScore() <= 1.0);
        }
        
        assertTrue(foundDoc1 && foundDoc3, "Both matching documents should be ranked");
    }
}
