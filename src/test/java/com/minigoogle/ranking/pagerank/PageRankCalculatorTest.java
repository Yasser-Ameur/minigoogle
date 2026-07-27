package com.minigoogle.ranking.pagerank;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for PageRankCalculator functionality. */
class PageRankCalculatorTest {

    @Test
    void testSimpleChainGraph() {
        // A → B → C
        GraphBuilder graph = new GraphBuilder();
        graph.addEdge(0, 1);
        graph.addEdge(1, 2);
        graph.addNode(2); // C is a sink

        PageRankCalculator calc = new PageRankCalculator();
        Map<Integer, Double> ranks = calc.compute(graph);

        assertEquals(3, ranks.size());
        // All ranks should be positive
        for (double rank : ranks.values()) {
            assertTrue(rank > 0, "All PageRank scores should be positive");
        }

        // Sum should be approximately 1.0
        double sum = ranks.values().stream().mapToDouble(d -> d).sum();
        assertEquals(1.0, sum, 0.01, "PageRank scores should sum to approximately 1.0");
    }

    @Test
    void testCyclicGraph() {
        // A → B → C → A (cycle)
        GraphBuilder graph = new GraphBuilder();
        graph.addEdge(0, 1);
        graph.addEdge(1, 2);
        graph.addEdge(2, 0);

        PageRankCalculator calc = new PageRankCalculator();
        Map<Integer, Double> ranks = calc.compute(graph);

        // In a symmetric cycle, all nodes should have equal rank
        double rankA = ranks.get(0);
        double rankB = ranks.get(1);
        double rankC = ranks.get(2);

        assertEquals(rankA, rankB, 0.01, "Nodes in a symmetric cycle should have similar rank");
        assertEquals(rankB, rankC, 0.01, "Nodes in a symmetric cycle should have similar rank");
    }

    @Test
    void testHubAuthority() {
        // Hub node 0 links to 1, 2, 3. Nodes 1, 2, 3 all link to node 4.
        GraphBuilder graph = new GraphBuilder();
        graph.addEdge(0, 1);
        graph.addEdge(0, 2);
        graph.addEdge(0, 3);
        graph.addEdge(1, 4);
        graph.addEdge(2, 4);
        graph.addEdge(3, 4);
        graph.addNode(4); // Sink

        PageRankCalculator calc = new PageRankCalculator();
        Map<Integer, Double> ranks = calc.compute(graph);

        // Node 4 should have the highest rank (3 incoming links)
        double rank4 = ranks.get(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(rank4 > ranks.get(i),
                    "Node 4 (authority) should rank higher than node " + i);
        }
    }
}
