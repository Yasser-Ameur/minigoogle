package com.minigoogle.semantic.spell;

/**
 * Computes Levenshtein edit distance between two strings.
 *
 * <p>Uses dynamic programming with O(m*n) time complexity and O(min(m,n))
 * space complexity by only keeping two rows of the DP matrix at a time.</p>
 */
public final class Levenshtein {

    private Levenshtein() {
        // Utility class
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     *
     * @param a The first string.
     * @param b The second string.
     * @return The minimum number of single-character edits (insertions, deletions, substitutions).
     */
    public static int distance(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        // Ensure a is the shorter string for space optimization
        if (a.length() > b.length()) {
            String temp = a;
            a = b;
            b = temp;
        }

        int m = a.length();
        int n = b.length();
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];

        for (int i = 0; i <= m; i++) {
            prev[i] = i;
        }

        for (int j = 1; j <= n; j++) {
            curr[0] = j;
            for (int i = 1; i <= m; i++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    curr[i] = prev[i - 1];
                } else {
                    curr[i] = 1 + Math.min(prev[i - 1],
                            Math.min(prev[i], curr[i - 1]));
                }
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[m];
    }

    /**
     * Computes a similarity score between two strings based on Levenshtein distance.
     *
     * @param a The first string.
     * @param b The second string.
     * @return A value in [0.0, 1.0] where 1.0 means identical strings.
     */
    public static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) distance(a, b) / maxLen);
    }
}
