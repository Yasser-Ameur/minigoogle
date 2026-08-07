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
    private final boolean diversifyEnabled;

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
        this(bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths, DEFAULT_TOP_K, true);
    }

    public RankingPipeline(BM25Parameters bm25Params,
                           Map<Integer, Double> pageRankScores,
                           Map<Integer, String> docUrls,
                           Map<Integer, String> docTitles,
                           Map<Integer, String> docBodies,
                           Map<Integer, Integer> docLengths,
                           boolean diversifyEnabled) {
        this(bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths,
                DEFAULT_TOP_K, diversifyEnabled);
    }

    public RankingPipeline(BM25Parameters bm25Params,
                           Map<Integer, Double> pageRankScores,
                           Map<Integer, String> docUrls,
                           Map<Integer, String> docTitles,
                           Map<Integer, String> docBodies,
                           Map<Integer, Integer> docLengths,
                           int topK) {
        this(bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths, topK, true);
    }

    public RankingPipeline(BM25Parameters bm25Params,
                           Map<Integer, Double> pageRankScores,
                           Map<Integer, String> docUrls,
                           Map<Integer, String> docTitles,
                           Map<Integer, String> docBodies,
                           Map<Integer, Integer> docLengths,
                           int topK,
                           boolean diversifyEnabled) {
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
        this.diversifyEnabled = diversifyEnabled;
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

        // 1. Accumulate per-document term frequencies in a single pass over the
        //    (docId-sorted) posting lists. Posting lists are sorted by document
        //    id, so each posting contributes exactly once: O(total postings)
        //    instead of O(candidateDocs x total postings).
        Map<Integer, Map<String, Integer>> tfByDoc = new HashMap<>();
        for (String term : queryTerms) {
            PostingList pl = candidatePostings.get(term);
            if (pl == null) {
                continue;
            }
            for (Posting p : pl.getPostings()) {
                tfByDoc.computeIfAbsent(p.getDocumentId(), id -> new HashMap<>())
                        .put(term, p.getFrequency());
            }
        }

        if (tfByDoc.isEmpty()) {
            return List.of();
        }

        // 2. Compute BM25 score for each candidate
        Map<Integer, Double> rawBm25Scores = new HashMap<>(tfByDoc.size());
        for (Map.Entry<Integer, Map<String, Integer>> e : tfByDoc.entrySet()) {
            int docId = e.getKey();
            int docLength = docLengths.getOrDefault(docId, 1);
            double score = bm25Calculator.scoreDocument(
                    queryTerms, e.getValue(), docLength, documentFrequencies);
            rawBm25Scores.put(docId, score);
        }

        // 3. Collect PageRank scores for candidates
        Map<Integer, Double> rawPageRankScores = new HashMap<>(tfByDoc.size());
        for (int docId : tfByDoc.keySet()) {
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

        for (int docId : tfByDoc.keySet()) {
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

        // 8. Apply domain diversification (skippable: reorders by domain, which
        //    perturbs ranking metrics and is meaningless when every document
        //    shares one synthetic domain).
        if (diversifyEnabled) {
            return diversityFilter.diversify(topResults);
        }
        return topResults;
    }
}
