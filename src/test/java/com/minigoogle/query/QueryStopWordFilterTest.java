package com.minigoogle.query;

import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.lexer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Query analysis must match index analysis. The indexer drops stop words, so a
 * stop word in an implicit-AND query makes the conjunction unsatisfiable — which
 * returned zero results for all 50 TREC-COVID queries.
 */
class QueryStopWordFilterTest {

    private final Lexer lexer = new Lexer();
    private final QueryStopWordFilter filter = new QueryStopWordFilter();

    private List<String> words(List<Token> tokens) {
        return tokens.stream()
                .filter(t -> t.type() == TokenType.WORD)
                .map(Token::value)
                .toList();
    }

    @Test
    void dropsStopWordsFromANaturalLanguageQuery() {
        List<Token> filtered = filter.filter(lexer.tokenize("what is the origin of COVID-19"));

        List<String> kept = words(filtered);
        // "what" is deliberately not asserted: StopWordFilter's list has 33
        // entries and does not include question words. See
        // ENGINEERING_FINDINGS.md -- that gap is a separate finding.
        assertFalse(kept.contains("is"), kept.toString());
        assertFalse(kept.contains("the"), kept.toString());
        assertFalse(kept.contains("of"), kept.toString());
        assertTrue(kept.stream().anyMatch(w -> w.equalsIgnoreCase("origin")), kept.toString());
        assertTrue(kept.stream().anyMatch(w -> w.toLowerCase().contains("covid")), kept.toString());
    }

    @Test
    void keepsQueriesThatContainNoStopWordsUnchanged() {
        List<Token> tokens = lexer.tokenize("coronavirus incubation period");
        assertEquals(words(tokens), words(filter.filter(tokens)));
    }

    @Test
    void isCaseInsensitiveLikeTheIndexer() {
        // The indexer folds case before testing, so query analysis must too.
        List<String> kept = words(filter.filter(lexer.tokenize("The Origin Of Covid")));
        assertFalse(kept.stream().anyMatch(w -> w.equalsIgnoreCase("the")), kept.toString());
        assertFalse(kept.stream().anyMatch(w -> w.equalsIgnoreCase("of")), kept.toString());
        assertEquals(2, kept.size(), kept.toString());
    }

    @Test
    void leavesExplicitBooleanQueriesUntouched() {
        // Removing an operand would leave a dangling operator and silently
        // rewrite the expression the user asked for.
        List<Token> tokens = lexer.tokenize("covid AND the");
        assertSame(tokens, filter.filter(tokens));

        List<Token> orQuery = lexer.tokenize("covid OR the");
        assertSame(orQuery, filter.filter(orQuery));

        List<Token> notQuery = lexer.tokenize("covid NOT the");
        assertSame(notQuery, filter.filter(notQuery));
    }

    @Test
    void leavesParenthesisedQueriesUntouched() {
        List<Token> tokens = lexer.tokenize("(covid OR sars) AND origin");
        assertSame(tokens, filter.filter(tokens));
    }

    @Test
    void aQueryOfOnlyStopWordsIsLeftUnchanged() {
        // Filtering everything away would turn this into a null query, which the
        // caller treats differently from an empty result.
        List<Token> tokens = lexer.tokenize("the of is");
        assertSame(tokens, filter.filter(tokens));
    }

    @Test
    void nonStopQuestionWordsSurviveFiltering() {
        // Documents the actual boundary: the stop list covers function words
        // like "is"/"the"/"of" but not question words, so "what" is still
        // required by the implicit AND. This is why stop-word filtering alone
        // does not fix natural-language retrieval.
        List<String> kept = words(filter.filter(lexer.tokenize("what is the origin")));
        assertTrue(kept.contains("what"), kept.toString());
        assertTrue(kept.contains("origin"), kept.toString());
        assertEquals(2, kept.size(), kept.toString());
    }

    @Test
    void phraseTokensAreNeverModified() {
        List<Token> filtered = filter.filter(lexer.tokenize("\"the common cold\" treatment"));

        List<Token> phrases = filtered.stream()
                .filter(t -> t.type() == TokenType.PHRASE)
                .toList();
        assertEquals(1, phrases.size(), "the phrase must survive intact");
        assertEquals("the common cold", phrases.get(0).value(),
                "a phrase is positional; stripping words from it would change adjacency");
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertEquals(List.of(), filter.filter(List.of()));
        assertEquals(null, filter.filter(null));
    }
}
