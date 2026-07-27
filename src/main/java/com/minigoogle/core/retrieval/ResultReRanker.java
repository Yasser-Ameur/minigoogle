package com.minigoogle.core.retrieval;

import java.util.List;

public interface ResultReRanker {
    List<RetrievalResult> rerankResults(String query, List<RetrievalResult> candidates);
}
