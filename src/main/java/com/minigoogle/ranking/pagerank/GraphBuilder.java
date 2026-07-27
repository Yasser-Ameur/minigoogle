package com.minigoogle.ranking.pagerank;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a directed graph of the web from document link relationships.
 * Each node is a document ID, and edges represent hyperlinks.
 */
public class GraphBuilder {

    private final Map<Integer, List<Integer>> outgoingLinks = new HashMap<>();
    private final Map<Integer, List<Integer>> incomingLinks = new HashMap<>();

    /**
     * Adds an edge from source document to target document.
     *
     * @param fromDocId Source document ID.
     * @param toDocId   Target document ID.
     */
    public void addEdge(int fromDocId, int toDocId) {
        outgoingLinks.computeIfAbsent(fromDocId, k -> new ArrayList<>()).add(toDocId);
        incomingLinks.computeIfAbsent(toDocId, k -> new ArrayList<>()).add(fromDocId);
        // Ensure both nodes exist in outgoing map
        outgoingLinks.putIfAbsent(toDocId, new ArrayList<>());
        incomingLinks.putIfAbsent(fromDocId, new ArrayList<>());
    }

    /**
     * Registers a node with no outgoing links (a sink/dangling node).
     */
    public void addNode(int docId) {
        outgoingLinks.putIfAbsent(docId, new ArrayList<>());
        incomingLinks.putIfAbsent(docId, new ArrayList<>());
    }

    public Map<Integer, List<Integer>> getOutgoingLinks() {
        return outgoingLinks;
    }

    public Map<Integer, List<Integer>> getIncomingLinks() {
        return incomingLinks;
    }

    public int getNodeCount() {
        return outgoingLinks.size();
    }
}
