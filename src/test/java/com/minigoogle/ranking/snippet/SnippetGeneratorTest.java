package com.minigoogle.ranking.snippet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for SnippetGenerator functionality. */
class SnippetGeneratorTest {

    @Test
    void testHighlighting() {
        SnippetGenerator generator = new SnippetGenerator();
        String body = "This is a document about modern compiler optimization techniques.";
        
        String snippet = generator.generate(body, List.of("compiler", "optimization"));
        
        // Assert that the snippet contains the bolded versions of the terms
        assertTrue(snippet.contains("**compiler**"), "Should highlight 'compiler'");
        assertTrue(snippet.contains("**optimization**"), "Should highlight 'optimization'");
    }

    @Test
    void testLongDocumentSnippet() {
        SnippetGenerator generator = new SnippetGenerator();
        String prefix = "This is a long introductory text that doesn't contain any keywords. ".repeat(10);
        String keywords = "However, this section talks about java and how java compiler is fast. ";
        String suffix = "This is a long concluding text that doesn't contain any keywords. ".repeat(10);
        
        String body = prefix + keywords + suffix;
        String snippet = generator.generate(body, List.of("java", "compiler"));
        
        // Assert the snippet captures the dense window
        assertTrue(snippet.contains("**java**"), "Should capture java");
        assertTrue(snippet.contains("**compiler**"), "Should capture compiler");
        assertTrue(snippet.startsWith("..."), "Should have prefix ellipsis");
        assertTrue(snippet.endsWith("..."), "Should have suffix ellipsis");
    }

    @Test
    void testNoMatch() {
        SnippetGenerator generator = new SnippetGenerator();
        String body = "This document has nothing to do with the query.";
        
        String snippet = generator.generate(body, List.of("java"));
        
        // Should just return the beginning of the document
        assertEquals("This document has nothing to do with the query.", snippet);
    }
}
