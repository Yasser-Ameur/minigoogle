package com.minigoogle.core.retrieval;

import java.util.List;

public interface CandidateRetriever {
    List<RetrievalResult> retrieveCandidates(String query, int maxCandidates);
}
