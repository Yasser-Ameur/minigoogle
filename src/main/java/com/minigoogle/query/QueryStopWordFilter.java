package com.minigoogle.query;

import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stopwords.StopWordFilter;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes stop words from a query's token stream so that query analysis matches
 * index analysis.
 *
 * <h2>Why this exists</h2>
 * {@code IndexBuilder} drops stop words before writing postings, so terms like
 * {@code the} and {@code of} are never in the dictionary. The query path did not
 * drop them, and the parser joins adjacent words with implicit AND — so
 * {@code "what is the origin of COVID-19"} became
 * {@code what AND is AND the AND origin AND of AND covid AND 19}, every stop word
 * resolved to an empty posting list, and the intersection was empty. A single
 * stop word anywhere in a query was enough to guarantee zero results. On BEIR
 * TREC-COVID that was all 50 of 50 queries.
 *
 * <p>Dropping these terms is not a relaxation of the user's intent: the index
 * cannot represent them at all, so requiring one in a conjunction is
 * unsatisfiable by construction.</p>
 *
 * <h2>Scope</h2>
 * Filtering applies only when the query carries no explicit boolean structure —
 * no {@code AND}/{@code OR}/{@code NOT} and no parentheses. That covers every
 * natural-language query, which is the case that was broken. When a user writes
 * explicit operators the token stream is returned untouched, because removing an
 * operand there would leave a dangling operator and silently change the boolean
 * expression they wrote.
 *
 * <p>Phrase tokens are never modified. A phrase is matched positionally against
 * an index whose stop words were replaced by empty placeholders, so a phrase
 * containing one still cannot match; stripping words out of a phrase would
 * change which documents are adjacent and is not a fix for that. See
 * {@code ENGINEERING_FINDINGS.md}.</p>
 */
public final class QueryStopWordFilter {

    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final StopWordFilter stopWords = new StopWordFilter();

    /**
     * @param tokens the lexed query
     * @return the tokens with stop words removed, or {@code tokens} unchanged
     *         when the query uses explicit boolean operators, when every term is
     *         a stop word, or when nothing was removed
     */
    public List<Token> filter(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty() || hasExplicitOperators(tokens)) {
            return tokens;
        }

        List<Token> kept = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            if (token.type() == TokenType.WORD && isStopWord(token.value())) {
                continue;
            }
            kept.add(token);
        }

        // A query made entirely of stop words has no indexable term. Returning
        // the original stream keeps the existing behaviour for it (an empty
        // result plus the spell-correction fallback) rather than turning it into
        // a null query, which the caller treats differently.
        if (kept.isEmpty()) {
            return tokens;
        }
        return kept;
    }

    /** Applies the same normalization the indexer applies before its stop-word test. */
    private boolean isStopWord(String word) {
        return stopWords.isStopWord(caseFolder.fold(normalizer.normalize(word)));
    }

    private static boolean hasExplicitOperators(List<Token> tokens) {
        for (Token token : tokens) {
            switch (token.type()) {
                case AND, OR, NOT, LEFT_PAREN, RIGHT_PAREN -> {
                    return true;
                }
                default -> {
                    // keep scanning
                }
            }
        }
        return false;
    }
}
