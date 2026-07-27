package com.minigoogle.core.retrieval;

import java.util.List;

public interface RetrievalEngine {
    List<RetrievalResult> retrieve(String query, int topK);
}
