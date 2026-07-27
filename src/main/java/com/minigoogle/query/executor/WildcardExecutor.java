package com.minigoogle.query.executor;

import com.minigoogle.indexer.inverted.InvertedIndex;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.trie.Trie;

import java.util.List;

/**
 * Executes wildcard queries (e.g. "algorith*").
 *
 * Algorithm:
 * 1. Strip the trailing '*' to obtain the prefix.
 * 2. Look up the prefix in the Trie to collect every matching dictionary term
 *    via depth-first search.
 * 3. For each matched term, retrieve its posting list from the inverted index.
 * 4. Union all posting lists to produce the final result.
 *
 * Complexity: O(length of prefix) for Trie traversal, plus the cost of
 * posting-list unions proportional to the number of matching terms.
 */
public class WildcardExecutor {

    private final Trie trie;
    private final InvertedIndex index;
    private final BooleanExecutor booleanExecutor;

    public WildcardExecutor(Trie trie, InvertedIndex index) {
        this.trie = trie;
        this.index = index;
        this.booleanExecutor = new BooleanExecutor();
    }

    /**
     * Executes a wildcard query.
     *
     * @param pattern A pattern ending with '*' (e.g. "algorith*").
     * @return The merged posting list of all documents matching any term
     *         that starts with the given prefix.
     */
    public PostingList execute(String pattern) {
        String prefix = stripWildcard(pattern);
        List<String> matchingTerms = trie.findPrefixes(prefix);

        PostingList result = new PostingList();
        for (String term : matchingTerms) {
            PostingList termPostings = index.getIndex().get(term);
            if (termPostings != null) {
                result = booleanExecutor.union(result, termPostings);
            }
        }
        return result;
    }

    /**
     * Returns true if the pattern is a wildcard pattern (contains '*').
     */
    public static boolean isWildcard(String pattern) {
        return pattern != null && pattern.endsWith("*");
    }

    private String stripWildcard(String pattern) {
        if (pattern == null) {
            return "";
        }
        return pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
    }
}
