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
    void testImplicitOperatorDefaultsToOr() {
        // Adjacent terms are OR-ed so partial matches still compete, ranked by
        // BM25. The previous AND default made a multi-term natural-language
        // query unsatisfiable: on BEIR TREC-COVID it returned zero results for
        // all 50 queries.
        Lexer lexer = new Lexer();
        QueryNode node = new Parser(lexer.tokenize("java compiler")).parse();

        assertTrue(node instanceof OrNode, "adjacent terms must default to OR");
        OrNode orNode = (OrNode) node;
        assertEquals("java", ((WordNode) orNode.left()).word());
        assertEquals("compiler", ((WordNode) orNode.right()).word());
    }

    @Test
    void testImplicitAndStillAvailableExplicitly() {
        // Conjunctive matching is still reachable for boolean filtering.
        Lexer lexer = new Lexer();
        QueryNode node = new Parser(lexer.tokenize("java compiler"),
                Parser.ImplicitOperator.AND).parse();

        assertTrue(node instanceof AndNode);
        AndNode andNode = (AndNode) node;
        assertEquals("java", ((WordNode) andNode.left()).word());
        assertEquals("compiler", ((WordNode) andNode.right()).word());
    }

    @Test
    void testExplicitAndIsAlwaysHonoured() {
        Lexer lexer = new Lexer();
        QueryNode node = new Parser(lexer.tokenize("java AND compiler")).parse();
        assertTrue(node instanceof AndNode,
                "an explicit AND must mean AND regardless of the implicit default");
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
