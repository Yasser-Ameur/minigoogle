package com.minigoogle.core.retrieval;

import java.util.List;

public interface ResultRanker {
    List<RetrievalResult> rank(List<String> queryTerms, List<RetrievalResult> candidates);
}
