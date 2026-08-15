package com.minigoogle.query.parser;

import com.minigoogle.query.ast.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ASTBuilder (query string to AST) functionality. */
class ASTBuilderTest {

    @Test
    void testSimpleWordQuery() {
        QueryNode node = ASTBuilder.build("hello");
        assertNotNull(node);
        assertTrue(node instanceof WordNode);
        assertEquals("hello", ((WordNode) node).word());
    }

    @Test
    void testAndQuery() {
        QueryNode node = ASTBuilder.build("java AND compiler");
        assertNotNull(node);
        assertTrue(node instanceof AndNode);
    }

    @Test
    void testOrQuery() {
        QueryNode node = ASTBuilder.build("java OR python");
        assertNotNull(node);
        assertTrue(node instanceof OrNode);
    }

    @Test
    void testNotQuery() {
        QueryNode node = ASTBuilder.build("NOT java");
        assertNotNull(node);
        assertTrue(node instanceof NotNode);
    }

    @Test
    void testPhraseQuery() {
        QueryNode node = ASTBuilder.build("\"machine learning\"");
        assertNotNull(node);
        assertTrue(node instanceof PhraseNode);
        assertEquals("machine learning", ((PhraseNode) node).phrase());
    }

    @Test
    void testParentheses() {
        QueryNode node = ASTBuilder.build("(java OR python) AND compiler");
        assertNotNull(node);
        assertTrue(node instanceof AndNode);
    }

    @Test
    void testImplicitOperatorDefaultsToOr() {
        // See Parser.ImplicitOperator: OR is the bag-of-words retrieval model.
        QueryNode node = ASTBuilder.build("java compiler");
        assertNotNull(node);
        assertTrue(node instanceof OrNode, "adjacent terms must default to OR");
    }

    @Test
    void testExplicitAndStillBuildsAnAndNode() {
        QueryNode node = ASTBuilder.build("java AND compiler");
        assertNotNull(node);
        assertTrue(node instanceof AndNode);
    }

    @Test
    void testNullInput() {
        assertNull(ASTBuilder.build(null));
        assertNull(ASTBuilder.build(""));
        assertNull(ASTBuilder.build("   "));
    }

    @Test
    void testComplexQuery() {
        QueryNode node = ASTBuilder.build("(machine learning OR AI) AND google");
        assertNotNull(node);
        assertTrue(node instanceof AndNode);
    }
}
