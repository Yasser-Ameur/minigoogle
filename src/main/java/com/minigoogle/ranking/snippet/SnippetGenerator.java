package com.minigoogle.ranking.snippet;

import com.minigoogle.core.retrieval.SnippetBuilder;
import java.util.List;
import java.util.Locale;

/**
 * Generates search result snippets by extracting the most relevant
 * ~150-character window from the document body and highlighting query terms.
 */
public class SnippetGenerator implements SnippetBuilder {

    private static final int SNIPPET_LENGTH = 150;

    /**
     * Generates a snippet from the document body highlighting query terms.
     *
     * Algorithm: Slide a window across the document text, scoring each position
     * by how many query terms fall within the window. Return the best window
     * with matched terms wrapped in **bold** markers.
     *
     * @param body       The full document body text.
     * @param queryTerms List of stemmed/lowered query terms.
     * @return A snippet string with highlighted terms, or an empty string if body is null/empty.
     */
    public String generate(String body, List<String> queryTerms) {
        return buildSnippet(body, queryTerms);
    }

    @Override
    public String buildSnippet(String body, List<String> queryTerms) {
        if (body == null || body.isEmpty() || queryTerms == null || queryTerms.isEmpty()) {
            return body != null && body.length() > SNIPPET_LENGTH
                    ? body.substring(0, SNIPPET_LENGTH) + "..."
                    : (body != null ? body : "");
        }

        String lowerBody = body.toLowerCase(Locale.ROOT);

        // Find the best starting position (most query terms in window),
        // breaking ties by earlier window start, then by earliest term appearance.
        int bestStart = 0;
        int bestScore = 0;
        double bestEffectiveScore = -1;

        for (int i = 0; i <= Math.max(0, body.length() - SNIPPET_LENGTH); i++) {
            int end = Math.min(i + SNIPPET_LENGTH, body.length());
            String window = lowerBody.substring(i, end);
            int score = 0;
            int firstTermPos = window.length();
            for (String term : queryTerms) {
                int pos = window.indexOf(term.toLowerCase(Locale.ROOT));
                if (pos != -1) {
                    score++;
                    if (pos < firstTermPos) firstTermPos = pos;
                }
            }
            double effectiveScore = score + (1.0 - (double) firstTermPos / SNIPPET_LENGTH) * 0.5;
            if (effectiveScore > bestEffectiveScore) {
                bestEffectiveScore = effectiveScore;
                bestScore = score;
                bestStart = i;
            }
        }

        int snippetEnd = Math.min(bestStart + SNIPPET_LENGTH, body.length());
        String snippet = body.substring(bestStart, snippetEnd);

        // Highlight query terms (case-insensitive replacement)
        for (String term : queryTerms) {
            String lowerTerm = term.toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            String lowerSnippet = snippet.toLowerCase(Locale.ROOT);
            int lastIdx = 0;
            int idx;
            while ((idx = lowerSnippet.indexOf(lowerTerm, lastIdx)) != -1) {
                sb.append(snippet, lastIdx, idx);
                sb.append("**");
                sb.append(snippet, idx, idx + lowerTerm.length());
                sb.append("**");
                lastIdx = idx + lowerTerm.length();
            }
            sb.append(snippet.substring(lastIdx));
            snippet = sb.toString();
        }

        // Add ellipsis indicators
        String prefix = bestStart > 0 ? "..." : "";
        String suffix = snippetEnd < body.length() ? "..." : "";

        return prefix + snippet + suffix;
    }
}
