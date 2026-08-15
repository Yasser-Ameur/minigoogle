package com.minigoogle.query.lexer;

import java.util.List;
import java.util.Locale;

/**
 * Produces a canonical cache key for a query string from its lexical token
 * stream, so that two queries share a key <em>iff</em> they parse identically.
 *
 * <p>Plain lowercasing collapses the uppercase-only boolean operators into the
 * lowercase words, so {@code cat AND dog} (boolean AND) and {@code cat and dog}
 * (implicit AND over three words) collide. Rebuilding the key from the
 * {@link Lexer} token stream preserves operator identity: words are lowercased,
 * phrases are lowercased inside their quotes, and {@code AND}/{@code OR}/
 * {@code NOT} operators are kept uppercase. Whitespace variants of the same
 * query still collapse to one key because the lexer discards whitespace.</p>
 */
public final class QueryKey {

    private QueryKey() {
    }

    /**
     * Canonical cache key for {@code query}.
     *
     * @param query the raw query string, may be null
     * @return the canonical key (empty string for null/empty input)
     */
    public static String canonicalize(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<Token> tokens = new Lexer().tokenize(query);
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Token token : tokens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            switch (token.type()) {
                case WORD -> sb.append(token.value().toLowerCase(Locale.ROOT));
                case PHRASE -> sb.append('"').append(collapse(token.value())).append('"');
                case LEFT_PAREN -> sb.append('(');
                case RIGHT_PAREN -> sb.append(')');
                case AND, OR, NOT -> sb.append(token.value());
                default -> sb.append(token.value());
            }
        }
        return sb.toString();
    }

    private static String collapse(String phrase) {
        return phrase.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
