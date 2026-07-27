package com.minigoogle.ranking.pipeline;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.ranking.bm25.BM25Calculator;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.diversification.DiversityFilter;
import com.minigoogle.ranking.fusion.ScoreFusion;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.normalization.ScoreNormalizer;
import com.minigoogle.ranking.snippet.SnippetGenerator;

import java.util.*;

/**
 * Orchestrates the full ranking pipeline:
 *   Candidate Documents → BM25 → PageRank lookup → Normalize → Fuse → Diversify → Snippet → Top-K
 *
 * This is the central entry point for scoring and ranking search results.
 */
public class RankingPipeline {

    private static final int DEFAULT_TOP_K = 20;

    private final BM25Calculator bm25Calculator;
    private final ScoreNormalizer normalizer;
    private final ScoreFusion fusion;
    private final DiversityFilter diversityFilter;
    private final SnippetGenerator snippetGenerator;
    private final Map<Integer, Double> pageRankScores;
    private final int topK;

    // Document metadata needed for scoring
    private final Map<Integer, String> docUrls;
    private final Map<Integer, String> docTitles;
    private final Map<Integer, String> docBodies;
    private final Map<Integer, Integer> docLengths;

    public RankingPipeline(BM25Parameters bm25Params,
                           Map<Integer, Double> pageRankScores,
                           Map<Integer, String> docUrls,
                           Map<Integer, String> docTitles,
                           Map<Integer, String> docBodies,
                           Map<Integer, Integer> docLengths) {
        this(bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths, DEFAULT_TOP_K);
    }

    public RankingPipeline(BM25Parameters bm25Params,
                           Map<Integer, Double> pageRankScores,
                           Map<Integer, String> docUrls,
                           Map<Integer, String> docTitles,
                           Map<Integer, String> docBodies,
                           Map<Integer, Integer> docLengths,
                           int topK) {
        this.bm25Calculator = new BM25Calculator(bm25Params);
        this.normalizer = new ScoreNormalizer();
        this.fusion = new ScoreFusion();
        this.diversityFilter = new DiversityFilter();
        this.snippetGenerator = new SnippetGenerator();
        this.pageRankScores = pageRankScores;
        this.docUrls = docUrls;
        this.docTitles = docTitles;
        this.docBodies = docBodies;
        this.docLengths = docLengths;
        this.topK = topK;
    }

    /**
     * Ranks candidate documents for a given set of query terms.
     *
     * @param queryTerms         Stemmed/normalized query terms.
     * @param candidatePostings  Map of queryTerm → PostingList (from the query engine).
     * @param documentFrequencies Map of term → number of documents containing that term.
     * @return Top-K ranked and diversified results with snippets.
     */
    public List<RankedDocument> rank(List<String> queryTerms,
                                     Map<String, PostingList> candidatePostings,
                                     Map<String, Integer> documentFrequencies) {

        // 1. Collect all unique candidate document IDs
        Set<Integer> candidateDocIds = new HashSet<>();
        for (PostingList pl : candidatePostings.values()) {
            for (Posting p : pl.getPostings()) {
                candidateDocIds.add(p.getDocumentId());
            }
        }

        if (candidateDocIds.isEmpty()) {
            return List.of();
        }

        // 2. Compute BM25 score for each candidate
        Map<Integer, Double> rawBm25Scores = new HashMap<>();
        for (int docId : candidateDocIds) {
            Map<String, Integer> tfMap = new HashMap<>();
            for (String term : queryTerms) {
                PostingList pl = candidatePostings.get(term);
                if (pl != null) {
                    for (Posting p : pl.getPostings()) {
                        if (p.getDocumentId() == docId) {
                            tfMap.put(term, p.getFrequency());
                            break;
                        }
                    }
                }
            }

            int docLength = docLengths.getOrDefault(docId, 1);
            double score = bm25Calculator.scoreDocument(queryTerms, tfMap, docLength, documentFrequencies);
            rawBm25Scores.put(docId, score);
        }

        // 3. Collect PageRank scores for candidates
        Map<Integer, Double> rawPageRankScores = new HashMap<>();
        for (int docId : candidateDocIds) {
            rawPageRankScores.put(docId, pageRankScores.getOrDefault(docId, 0.0));
        }

        // 4. Normalize both score sets
        Map<Integer, Double> normBm25 = normalizer.normalize(rawBm25Scores);
        Map<Integer, Double> normPageRank = normalizer.normalize(rawPageRankScores);

        // 5. Fuse scores
        Map<Integer, Double> fusedScores = fusion.fuse(normBm25, normPageRank);

        // 6. Build RankedDocument list and select Top-K via min-heap
        PriorityQueue<RankedDocument> heap = new PriorityQueue<>(
                Comparator.comparingDouble(RankedDocument::finalScore));

        for (int docId : candidateDocIds) {
            String url = docUrls.getOrDefault(docId, "");
            String title = docTitles.getOrDefault(docId, "");
            String body = docBodies.getOrDefault(docId, "");
            String snippet = snippetGenerator.generate(body, queryTerms);

            RankedDocument ranked = new RankedDocument(
                    docId, url, title,
                    rawBm25Scores.getOrDefault(docId, 0.0),
                    rawPageRankScores.getOrDefault(docId, 0.0),
                    fusedScores.getOrDefault(docId, 0.0),
                    snippet
            );

            heap.offer(ranked);
            if (heap.size() > topK) {
                heap.poll(); // evict lowest score
            }
        }

        // 7. Extract from heap into sorted list (highest first)
        List<RankedDocument> topResults = new ArrayList<>(heap);
        topResults.sort(Comparator.comparingDouble(RankedDocument::finalScore).reversed());

        // 8. Apply domain diversification
        return diversityFilter.diversify(topResults);
    }
}
