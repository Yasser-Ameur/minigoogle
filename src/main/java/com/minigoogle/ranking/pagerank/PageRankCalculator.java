package com.minigoogle.ranking.pagerank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes PageRank scores using the iterative power method.
 *
 * The PageRank formula:
 *   PR(p) = (1 - d) / N + d × Σ [ PR(q) / L(q) ] for all q linking to p
 *
 * where d = damping factor (0.85), N = total pages, L(q) = number of outgoing links from q.
 *
 * Dangling nodes (no outgoing links) distribute their rank equally to all nodes.
 */
public class PageRankCalculator {

    private final double dampingFactor;
    private final int iterations;

    /**
     * Creates a PageRankCalculator with the given parameters.
     *
     * @param dampingFactor Probability of following a link (standard: 0.85).
     * @param iterations    Number of power iterations to run (standard: 40).
     */
    public PageRankCalculator(double dampingFactor, int iterations) {
        this.dampingFactor = dampingFactor;
        this.iterations = iterations;
    }

    /**
     * Creates a PageRankCalculator with standard defaults (d=0.85, 40 iterations).
     */
    public PageRankCalculator() {
        this(0.85, 40);
    }

    /**
     * Computes PageRank for all nodes in the graph.
     *
     * @param graph The link graph built by GraphBuilder.
     * @return Map of docId → PageRank score.
     */
    public Map<Integer, Double> compute(GraphBuilder graph) {
        int N = graph.getNodeCount();
        if (N == 0) return Map.of();

        Map<Integer, List<Integer>> outgoing = graph.getOutgoingLinks();
        Map<Integer, List<Integer>> incoming = graph.getIncomingLinks();

        // Initialize all ranks to 1/N
        Map<Integer, Double> ranks = new HashMap<>();
        double initialRank = 1.0 / N;
        for (int nodeId : outgoing.keySet()) {
            ranks.put(nodeId, initialRank);
        }

        double baseTeleport = (1.0 - dampingFactor) / N;

        for (int iter = 0; iter < iterations; iter++) {
            Map<Integer, Double> newRanks = new HashMap<>();

            // Compute dangling node contribution (nodes with no outgoing links)
            double danglingSum = 0.0;
            for (Map.Entry<Integer, List<Integer>> entry : outgoing.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    danglingSum += ranks.get(entry.getKey());
                }
            }
            double danglingContribution = dampingFactor * danglingSum / N;

            for (int nodeId : outgoing.keySet()) {
                double incomingContribution = 0.0;

                List<Integer> inLinks = incoming.getOrDefault(nodeId, List.of());
                for (int sourceId : inLinks) {
                    int outDegree = outgoing.get(sourceId).size();
                    if (outDegree > 0) {
                        incomingContribution += ranks.get(sourceId) / outDegree;
                    }
                }

                double newRank = baseTeleport + danglingContribution + dampingFactor * incomingContribution;
                newRanks.put(nodeId, newRank);
            }

            ranks = newRanks;
        }

        return ranks;
    }
}
