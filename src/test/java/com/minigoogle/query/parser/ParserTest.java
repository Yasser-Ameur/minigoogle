package com.minigoogle.query.parser;

import com.minigoogle.query.ast.*;
import com.minigoogle.query.lexer.Lexer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for query Parser functionality. */
class ParserTest {

    @Test
    void testBasicParse() {
        Lexer lexer = new Lexer();
        Parser parser = new Parser(lexer.tokenize("java AND compiler"));
        QueryNode node = parser.parse();
        
        assertTrue(node instanceof AndNode);
        AndNode andNode = (AndNode) node;
        assertTrue(andNode.left() instanceof WordNode);
        assertEquals("java", ((WordNode) andNode.left()).word());
        assertTrue(andNode.right() instanceof WordNode);
        assertEquals("compiler", ((WordNode) andNode.right()).word());
    }

    @Test
    void testImplicitAnd() {
        Lexer lexer = new Lexer();
        Parser parser = new Parser(lexer.tokenize("java compiler"));
        QueryNode node = parser.parse();
        
        assertTrue(node instanceof AndNode);
        AndNode andNode = (AndNode) node;
        assertEquals("java", ((WordNode) andNode.left()).word());
        assertEquals("compiler", ((WordNode) andNode.right()).word());
    }

    @Test
    void testPrecedence() {
        Lexer lexer = new Lexer();
        Parser parser = new Parser(lexer.tokenize("java OR python AND compiler"));
        QueryNode node = parser.parse();
        
        // AND has higher precedence, so it should be: java OR (python AND compiler)
        assertTrue(node instanceof OrNode);
        OrNode orNode = (OrNode) node;
        assertEquals("java", ((WordNode) orNode.left()).word());
        
        assertTrue(orNode.right() instanceof AndNode);
        AndNode andNode = (AndNode) orNode.right();
        assertEquals("python", ((WordNode) andNode.left()).word());
        assertEquals("compiler", ((WordNode) andNode.right()).word());
    }
}
