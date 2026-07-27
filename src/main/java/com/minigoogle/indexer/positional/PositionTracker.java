package com.minigoogle.indexer.positional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tracks token positions within documents to support phrase and proximity queries. */
public class PositionTracker {
    public Map<String, List<Integer>> trackPositions(List<String> tokens) {
        Map<String, List<Integer>> positions = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            // If token is empty (e.g. removed stop word left as empty or null), skip it
            if (token != null && !token.isEmpty()) {
                positions.computeIfAbsent(token, k -> new ArrayList<>()).add(i);
            }
        }
        return positions;
    }
}
