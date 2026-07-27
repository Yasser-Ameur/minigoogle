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
}
