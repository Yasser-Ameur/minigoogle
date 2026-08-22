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
        return present(rankToDepth(queryTerms, candidatePostings, documentFrequencies, topK),
                queryTerms, topK);
    }

    /**
     * Scores and orders the candidates, keeping the best {@code depth} of them,
     * and attaches <b>no snippets</b>.
     *
     * <p>This is the seam that separates ranking depth from presentation depth.
     * Hybrid ranking needs a deep lexical ranking to fuse against — the measured
     * cost of fusing only 20 lexical results is 4.7% NDCG@10 and a third of
     * candidate recall on full-corpus TREC-COVID — but the response still returns
     * a page of twenty. Building snippets here would make the deep ranking cost
     * proportional to {@code depth} rather than to what is actually returned, so
     * snippet construction is deferred to {@link #present}.</p>
     *
     * @param depth how many ranked documents to keep; the fusion depth for
     *              hybrid modes, and {@code topK} for the plain lexical path
     */
    public List<RankedDocument> rankToDepth(List<String> queryTerms,
                                            Map<String, PostingList> candidatePostings,
                                            Map<String, Integer> documentFrequencies,
                                            int depth) {

        // The total number of postings bounds the number of distinct candidates,
        // so the dense arrays below are sized once up front and never grow.
        int postingBudget = 0;
        for (String term : queryTerms) {
            PostingList pl = candidatePostings.get(term);
            if (pl != null) {
                postingBudget += pl.getPostings().size();
            }
        }

        if (postingBudget == 0) {
            return List.of();
        }

        // 1. Score every candidate in a single pass over the (docId-sorted)
        //    posting lists. Posting lists are sorted by document id, so each
        //    posting contributes exactly once: O(total postings) instead of
        //    O(candidateDocs x total postings).
        //
        //    Each candidate gets a dense slot, and every signal is one double[]
        //    indexed by that slot. The map from document id to slot is the only
        //    hashing left on this path: term frequencies are consumed as they
        //    are read rather than staged in a per-document map, and the raw,
        //    normalized and fused scores live in arrays rather than in a
        //    succession of boxed maps discarded before returning.
        //
        //    Two factors are hoisted out of the inner loop. IDF depends only on
        //    the term's document frequency, and the BM25 length normalization
        //    only on the document, so each is computed once per term and once
        //    per document rather than once per (document, term) pair.
        int[] docIds = new int[postingBudget];
        double[] lengthNorm = new double[postingBudget];
        double[] rawBm25 = new double[postingBudget];
        double[] rawPageRank = new double[postingBudget];
        Map<Integer, Integer> slotOf = new HashMap<>();
        int candidates = 0;

        for (String term : queryTerms) {
            PostingList pl = candidatePostings.get(term);
            if (pl == null) {
                continue;
            }
            int df = documentFrequencies.getOrDefault(term, 0);
            double idf = df > 0 ? bm25Calculator.idf(df) : 0.0;

            for (Posting p : pl.getPostings()) {
                int docId = p.getDocumentId();
                Integer known = slotOf.get(docId);
                int slot;
                if (known == null) {
                    slot = candidates++;
                    slotOf.put(docId, slot);
                    docIds[slot] = docId;
                    lengthNorm[slot] = bm25Calculator.lengthNormalization(
                            docLengths.getOrDefault(docId, 1));
                    rawPageRank[slot] = pageRankScores.getOrDefault(docId, 0.0);
                } else {
                    slot = known;
                }

                int tf = p.getFrequency();
                if (tf > 0 && df > 0) {
                    rawBm25[slot] += bm25Calculator.termScore(idf, tf, lengthNorm[slot]);
                }
            }
        }

        // 2. Normalize both signals and fuse them. The normalized values are
        //    scratch: only the fused score is read afterwards, so it is written
        //    over the normalized BM25 array rather than into a third one.
        double[] fused = Arrays.copyOf(rawBm25, candidates);
        double[] normPageRank = Arrays.copyOf(rawPageRank, candidates);
        normalizer.normalizeInPlace(fused, candidates);
        normalizer.normalizeInPlace(normPageRank, candidates);
        for (int i = 0; i < candidates; i++) {
            fused[i] = fusion.fuse(fused[i], normPageRank[i]);
        }

        // 3. Select the best `depth` candidates via a min-heap.
        //
        //    A RankedDocument is built only for a candidate that actually
        //    enters the heap, so allocation here is bounded by `depth` rather
        //    than by the size of the matched posting union.
        //
        //    Snippets are deliberately NOT built here. Snippet construction
        //    scans the document body and is by far the most expensive
        //    per-document step, yet neither the heap (which orders by
        //    finalScore) nor the diversity filter (which reads only the url)
        //    inspects the snippet. Snippets are filled in by present(), for
        //    survivors only.
        //
        //    Exact ties on the fused score do occur. Breaking them by document
        //    id costs nothing and makes a ranking reproducible; leaving them to
        //    whatever order the candidate iteration happened to produce, as
        //    this did before, is stable for a given build but arbitrary.
        Comparator<RankedDocument> bestFirst =
                Comparator.comparingDouble(RankedDocument::finalScore).reversed()
                        .thenComparingInt(RankedDocument::documentId);
        PriorityQueue<RankedDocument> heap = new PriorityQueue<>(
                Math.max(1, Math.min(depth, candidates)), bestFirst.reversed());

        for (int slot = 0; slot < candidates; slot++) {
            if (heap.size() < depth) {
                heap.offer(toRankedDocument(slot, docIds, rawBm25, rawPageRank, fused));
            } else if (depth > 0) {
                RankedDocument worst = heap.peek();
                if (fused[slot] > worst.finalScore()
                        || (fused[slot] == worst.finalScore()
                                && docIds[slot] < worst.documentId())) {
                    heap.poll(); // evict lowest score
                    heap.offer(toRankedDocument(slot, docIds, rawBm25, rawPageRank, fused));
                }
            }
        }

        // 4. Extract from heap into sorted list (highest first)
        List<RankedDocument> topResults = new ArrayList<>(heap);
        topResults.sort(bestFirst);
        return topResults;
    }

    /**
     * Builds the result record for one candidate slot, without a snippet.
     */
    private RankedDocument toRankedDocument(int slot,
                                            int[] docIds,
                                            double[] rawBm25,
                                            double[] rawPageRank,
                                            double[] fused) {
        int docId = docIds[slot];
        return new RankedDocument(
                docId,
                docUrls.getOrDefault(docId, ""),
                docTitles.getOrDefault(docId, ""),
                rawBm25[slot],
                rawPageRank[slot],
                fused[slot],
                ""
        );
    }

    /**
     * Turns a ranking into a response: keep the best {@code topK}, diversify,
     * then build snippets for the survivors only.
     *
     * <p>Called once, at the end, on whatever ranking the engine settled on —
     * lexical alone or the output of rank fusion. Snippet construction scans a
     * document body and is by far the most expensive per-document step, so it
     * runs exactly {@code min(topK, ranked.size())} times regardless of how deep
     * the ranking behind it was.</p>
     */
    public List<RankedDocument> present(List<RankedDocument> ranked,
                                        List<String> queryTerms,
                                        int topK) {
        List<RankedDocument> bounded = ranked.size() > topK
                ? new ArrayList<>(ranked.subList(0, topK))
                : ranked;

        // Domain diversification (skippable: reorders by domain, which perturbs
        // ranking metrics and is meaningless when every document shares one
        // synthetic domain). It applies to what is returned, as it always did.
        List<RankedDocument> selected = diversifyEnabled
                ? diversityFilter.diversify(bounded)
                : bounded;

        return withSnippets(selected, queryTerms);
    }

    /**
     * Returns {@code selected} with each document's snippet generated from its
     * body. Ordering and every score are preserved exactly; only the snippet
     * field changes.
     */
    private List<RankedDocument> withSnippets(List<RankedDocument> selected,
                                              List<String> queryTerms) {
        List<RankedDocument> withSnippets = new ArrayList<>(selected.size());
        for (RankedDocument doc : selected) {
            String body = docBodies.getOrDefault(doc.documentId(), "");
            withSnippets.add(new RankedDocument(
                    doc.documentId(),
                    doc.url(),
                    doc.title(),
                    doc.bm25Score(),
                    doc.pageRankScore(),
                    doc.finalScore(),
                    snippetGenerator.generate(body, queryTerms)
            ));
        }
        return withSnippets;
    }
}
