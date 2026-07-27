package com.minigoogle.core.retrieval;

import java.util.List;

public interface SnippetBuilder {
    String buildSnippet(String body, List<String> queryTerms);
}
