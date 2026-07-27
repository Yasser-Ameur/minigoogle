package com.minigoogle.core.coordinator;

import java.util.List;
import java.util.Map;

public interface SearchOrchestrator {
    List<Map<String, Object>> search(String query, int topK);
}
