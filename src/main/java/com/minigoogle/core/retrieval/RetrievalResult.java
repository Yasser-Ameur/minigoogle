package com.minigoogle.core.retrieval;

public record RetrievalResult(int documentId, double score, String url, String title) {
}
