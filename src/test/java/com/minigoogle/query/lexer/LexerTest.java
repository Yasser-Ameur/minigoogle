package com.minigoogle.query.lexer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for query Lexer functionality. */
class LexerTest {

    @Test
    void testBasicLexing() {
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize("(java OR python) AND compiler");
        
        assertEquals(7, tokens.size());
        assertEquals(TokenType.LEFT_PAREN, tokens.get(0).type());
        assertEquals(TokenType.WORD, tokens.get(1).type());
        assertEquals("java", tokens.get(1).value());
        assertEquals(TokenType.OR, tokens.get(2).type());
        assertEquals(TokenType.WORD, tokens.get(3).type());
        assertEquals("python", tokens.get(3).value());
        assertEquals(TokenType.RIGHT_PAREN, tokens.get(4).type());
        assertEquals(TokenType.AND, tokens.get(5).type());
        assertEquals(TokenType.WORD, tokens.get(6).type());
        assertEquals("compiler", tokens.get(6).value());
    }

    @Test
    void testPhraseLexing() {
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize("\"machine learning\" AND AI");
        
        assertEquals(3, tokens.size());
        assertEquals(TokenType.PHRASE, tokens.get(0).type());
        assertEquals("machine learning", tokens.get(0).value());
        assertEquals(TokenType.AND, tokens.get(1).type());
        assertEquals(TokenType.WORD, tokens.get(2).type());
        assertEquals("AI", tokens.get(2).value());
    }

    @Test
    void splitsHyphenatedWordsTheWayTheIndexerDoes() {
        // The indexer delimits on non-alphanumerics, so "COVID-19" is stored as
        // "covid" and "19". A query that kept the hyphen would look up a key the
        // dictionary never holds and silently contribute nothing to scoring.
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize("origin of COVID-19");

        assertEquals(4, tokens.size(), tokens.toString());
        assertEquals("COVID", tokens.get(2).value());
        assertEquals("19", tokens.get(3).value());
    }

    @Test
    void dropsTrailingPunctuationFromAWord() {
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize("immunity?");

        assertEquals(1, tokens.size(), tokens.toString());
        assertEquals("immunity", tokens.get(0).value());
    }

    @Test
    void dropsAGroupLeftEmptyBySplitting() {
        // "alpha (+)-" appears in BEIR scifact. Splitting empties the group, and
        // an empty group is not something the parser accepts.
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize("alpha (+)- thalassemia");

        assertEquals(2, tokens.size(), tokens.toString());
        assertEquals("alpha", tokens.get(0).value());
        assertEquals("thalassemia", tokens.get(1).value());
    }
}
