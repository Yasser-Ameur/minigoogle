package com.minigoogle.distributed.query.wand;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.result.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Block-Max WAND: an improvement over basic WAND.
 *
 * Per ARCHITECTURE.md Ch09 §20:
 *   Posting lists are divided into blocks.
 *   Each block stores the Maximum Possible Score for that block.
 *   If the threshold is 9.5 and a block's max is 3.1, we skip the
 *   entire block — thousands of documents at once.
 *
 * This dramatically improves throughput over document-level WAND.
 */
public class BlockMaxWAND {

    private final int topK;
    private final int blockSize;

    public BlockMaxWAND(int topK, int blockSize) {
        this.topK = topK;
        this.blockSize = blockSize;
    }

    public BlockMaxWAND(int topK) {
        this(topK, 128);
    }

    /**
     * Executes a Block-Max WAND query.
     *
     * @param postingLists  The posting lists for each query term.
     * @param termMaxScores The maximum BM25 score per term.
     * @return The top-K results sorted by descending score.
     */
    public List<SearchResult> execute(List<PostingList> postingLists, List<Double> termMaxScores) {
        if (postingLists.isEmpty()) {
            return List.of();
        }

        int numLists = postingLists.size();
        List<List<Posting>> lists = new ArrayList<>(numLists);
        double[] maxTermScores = new double[numLists];

        for (int i = 0; i < numLists; i++) {
            lists.add(postingLists.get(i).getPostings());
            maxTermScores[i] = i < termMaxScores.size() ? termMaxScores.get(i) : Double.MAX_VALUE;
        }

        // Precompute block max scores for each posting list
        List<double[]> blockMaxScores = new ArrayList<>(numLists);
        for (int i = 0; i < numLists; i++) {
            blockMaxScores.add(computeBlockMaxScores(lists.get(i), maxTermScores[i]));
        }

        int[] cursors = new int[numLists];
        int[] blockStarts = new int[numLists];
        PriorityQueue<PostingResult> topKHeap = new PriorityQueue<>(
                Comparator.comparingDouble(pr -> pr.score));
        double threshold = 0.0;

        while (true) {
            // Find minimum docId across all lists
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

            if (pivotList == -1) break;

            // Compute upper bound using block-max scores
            double upperBound = 0;
            for (int i = 0; i < numLists; i++) {
                List<Posting> list = lists.get(i);
                if (cursors[i] < list.size()) {
                    int docId = list.get(cursors[i]).getDocumentId();
                    if (docId == pivotDocId) {
                        upperBound += maxTermScores[i];
                    } else {
                        // Use block max score for the block this cursor is in
                        int blockIdx = cursors[i] / blockSize;
                        double[] blockMax = blockMaxScores.get(i);
                        if (blockIdx < blockMax.length) {
                            upperBound += blockMax[blockIdx];
                        }
                    }
                }
            }

            // Block-level early termination
            if (upperBound < threshold && topKHeap.size() >= topK) {
                // Try to skip entire blocks
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
                double actualScore = 0;
                for (int i = 0; i < numLists; i++) {
                    actualScore += lists.get(i).get(cursors[i]).getFrequency();
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

        List<SearchResult> results = new ArrayList<>();
        while (!topKHeap.isEmpty()) {
            PostingResult pr = topKHeap.poll();
            results.add(new SearchResult(null, pr.score));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    /**
     * Computes the maximum score within each block of a posting list.
     */
    private double[] computeBlockMaxScores(List<Posting> postings, double termMaxScore) {
        if (postings.isEmpty()) {
            return new double[0];
        }
        int numBlocks = (postings.size() + blockSize - 1) / blockSize;
        double[] blockMax = new double[numBlocks];

        for (int b = 0; b < numBlocks; b++) {
            int start = b * blockSize;
            int end = Math.min(start + blockSize, postings.size());
            double maxScore = 0;
            for (int i = start; i < end; i++) {
                double score = postings.get(i).getFrequency();
                if (score > maxScore) {
                    maxScore = score;
                }
            }
            blockMax[b] = maxScore;
        }
        return blockMax;
    }

    private record PostingResult(int documentId, double score) {
    }
}
