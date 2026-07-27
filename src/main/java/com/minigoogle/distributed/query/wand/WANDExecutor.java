package com.minigoogle.distributed.query.wand;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.result.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * WAND (Weighted AND) early termination executor.
 *
 * Per ARCHITECTURE.md Ch09 §19:
 *   With 2 million candidates, most cannot reach Top-20.
 *   WAND computes upper score bounds. If
 *     Maximum Possible Score < Current Top-K Threshold
 *   the document is skipped entirely, avoiding millions of useless
 *   BM25 computations.
 *
 * Algorithm:
 * 1. Maintain a pointer into each posting list.
 * 2. For each candidate document, compute its upper-bound score
 *    (sum of per-term maximum scores).
 * 3. If the upper bound is below the current top-K threshold,
 *    advance past the document without scoring it.
 * 4. Otherwise, compute the actual score and update the top-K heap.
 */
public class WANDExecutor {

    private final int topK;

    public WANDExecutor(int topK) {
        this.topK = topK;
    }

    /**
     * Executes a WAND query over multiple posting lists.
     *
     * @param postingLists The posting lists for each query term.
     * @param termMaxScores The maximum BM25 score per term (used as upper bounds).
     * @return The top-K results sorted by descending score.
     */
    public List<SearchResult> execute(List<PostingList> postingLists, List<Double> termMaxScores) {
        if (postingLists.isEmpty()) {
            return List.of();
        }

        int numLists = postingLists.size();
        List<List<Posting>> lists = new ArrayList<>(numLists);
        int[] cursors = new int[numLists];
        double[] maxTermScores = new double[numLists];

        for (int i = 0; i < numLists; i++) {
            lists.add(postingLists.get(i).getPostings());
            cursors[i] = 0;
            maxTermScores[i] = i < termMaxScores.size() ? termMaxScores.get(i) : Double.MAX_VALUE;
        }

        // Min-heap of top-K results (score ascending so we can poll the minimum)
        PriorityQueue<PostingResult> topKHeap = new PriorityQueue<>(
                Comparator.comparingDouble(pr -> pr.score));

        double threshold = 0.0;

        while (true) {
            // Find the document with the smallest current docId across all lists
            int pivotDocId = Integer.MAX_VALUE;
            int pivotList = -1;

            for (int i = 0; i < numLists; i++) {
                List<Posting> list = lists.get(i);
                if (cursors[i] < list.size()) {
                    int docId = list.get(cursors[i]).getDocumentId();
                    if (docId < pivotDocId) {
                        pivotDocId = docId;
                        pivotList = i;
                    }
                }
            }

            if (pivotList == -1) {
                break; // All lists exhausted
            }

            // Compute upper bound score for this document
            double upperBound = 0;
            for (int i = 0; i < numLists; i++) {
                List<Posting> list = lists.get(i);
                if (cursors[i] < list.size()) {
                    int docId = list.get(cursors[i]).getDocumentId();
                    if (docId == pivotDocId) {
                        // This list contains the document — use actual term score
                        upperBound += maxTermScores[i];
                    }
                    // If docId > pivotDocId, this list doesn't have pivotDoc — skip
                }
            }

            // Early termination check
            if (upperBound < threshold && topKHeap.size() >= topK) {
                // Advance all cursors that point to pivotDocId
                for (int i = 0; i < numLists; i++) {
                    List<Posting> list = lists.get(i);
                    if (cursors[i] < list.size() &&
                            list.get(cursors[i]).getDocumentId() == pivotDocId) {
                        cursors[i]++;
                    }
                }
                continue;
            }

            // Check if all lists have this document
            boolean allHave = true;
            for (int i = 0; i < numLists; i++) {
                List<Posting> list = lists.get(i);
                if (cursors[i] >= list.size() ||
                        list.get(cursors[i]).getDocumentId() != pivotDocId) {
                    allHave = false;
                    break;
                }
            }

            if (allHave) {
                // Compute actual score (sum of term frequencies as a simple proxy)
                double actualScore = 0;
                for (int i = 0; i < numLists; i++) {
                    Posting p = lists.get(i).get(cursors[i]);
                    actualScore += p.getFrequency();
                }

                if (topKHeap.size() < topK) {
                    topKHeap.add(new PostingResult(pivotDocId, actualScore));
                    if (topKHeap.size() == topK) {
                        threshold = topKHeap.peek().score;
                    }
                } else if (actualScore > threshold) {
                    topKHeap.poll();
                    topKHeap.add(new PostingResult(pivotDocId, actualScore));
                    threshold = topKHeap.peek().score;
                }
            }

            // Advance cursors pointing to pivotDocId
            for (int i = 0; i < numLists; i++) {
                List<Posting> list = lists.get(i);
                if (cursors[i] < list.size() &&
                        list.get(cursors[i]).getDocumentId() == pivotDocId) {
                    cursors[i]++;
                }
            }
        }

        // Extract and sort results by descending score
        List<SearchResult> results = new ArrayList<>();
        while (!topKHeap.isEmpty()) {
            PostingResult pr = topKHeap.poll();
            results.add(new SearchResult(null, pr.score));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    private record PostingResult(int documentId, double score) {
    }
}
