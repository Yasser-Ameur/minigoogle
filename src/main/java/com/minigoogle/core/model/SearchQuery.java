package com.minigoogle.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SearchQuery {
    private final String rawText;
    private final List<String> expandedTerms;
    private final int topK;
    private final int page;

    public SearchQuery(String rawText, List<String> expandedTerms, int topK, int page) {
        this.rawText = Objects.requireNonNull(rawText, "rawText");
        this.expandedTerms = expandedTerms != null ? List.copyOf(expandedTerms) : List.of();
        this.topK = topK > 0 ? topK : 20;
        this.page = page > 0 ? page : 1;
    }

    public SearchQuery(String rawText) {
        this(rawText, null, 20, 1);
    }

    public String rawText() { return rawText; }
    public List<String> expandedTerms() { return expandedTerms; }
    public int topK() { return topK; }
    public int page() { return page; }
    public int offset() { return (page - 1) * topK; }

    public SearchQuery withExpandedTerms(List<String> terms) {
        return new SearchQuery(rawText, terms, topK, page);
    }

    public SearchQuery withPage(int page) {
        return new SearchQuery(rawText, expandedTerms, topK, page);
    }
}
