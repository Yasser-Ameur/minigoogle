package com.minigoogle.semantic.expansion;

import com.minigoogle.query.ast.AndNode;
import com.minigoogle.query.ast.NotNode;
import com.minigoogle.query.ast.OrNode;
import com.minigoogle.query.ast.PhraseNode;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.ast.QueryVisitor;
import com.minigoogle.query.ast.WordNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for query-tree expansion: expansion must operate on the
 * parsed query model so quoted phrases and boolean structure survive.
 */
class QueryExpanderExpansionTest {

    private final QueryExpander expander = new QueryExpander();
    private final Lexer lexer = new Lexer();

    private QueryNode parse(String query) {
        return new Parser(lexer.tokenize(query)).parse();
    }

    @Test
    void phraseNodeIsPreservedByExpansion() {
        QueryNode expanded = expander.expand(parse("\"fast car\""), 4);
        assertInstanceOf(PhraseNode.class, expanded);
        assertEquals("fast car", ((PhraseNode) expanded).phrase());
    }

    @Test
    void phraseSurvivesExpansionEvenWhenWordsHaveSynonyms() {
        // Both "fast" and "car" have default synonyms; the phrase must not be
        // flattened into an OR-of-words the way raw-string expansion did.
        QueryNode expanded = expander.expand(parse("\"fast car\""), 4);
        assertEquals(new PhraseNode("fast car"), expanded);
    }

    @Test
    void wordLeafIsOrJoinedWithSynonyms() {
        QueryNode expanded = expander.expand(parse("fast"), 4);
        assertInstanceOf(OrNode.class, expanded);
        List<String> words = collectWords(expanded);
        assertTrue(words.contains("fast"));
        assertTrue(words.contains("quick"));
        assertTrue(words.contains("rapid"));
    }

    @Test
    void multiWordQueryKeepsParserImplicitAnd() {
        QueryNode expanded = expander.expand(parse("java compiler"), 4);
        assertInstanceOf(AndNode.class, expanded);
    }

    @Test
    void wordWithoutSynonymsStaysUnchanged() {
        QueryNode expanded = expander.expand(parse("zzzzyx"), 4);
        assertInstanceOf(WordNode.class, expanded);
        assertEquals("zzzzyx", ((WordNode) expanded).word());
    }

    private List<String> collectWords(QueryNode node) {
        List<String> words = new ArrayList<>();
        node.accept(new QueryVisitor<List<String>>() {
            @Override
            public List<String> visit(WordNode n) {
                words.add(n.word());
                return words;
            }

            @Override
            public List<String> visit(PhraseNode n) {
                return words;
            }

            @Override
            public List<String> visit(AndNode n) {
                n.left().accept(this);
                n.right().accept(this);
                return words;
            }

            @Override
            public List<String> visit(OrNode n) {
                n.left().accept(this);
                n.right().accept(this);
                return words;
            }

            @Override
            public List<String> visit(NotNode n) {
                n.operand().accept(this);
                return words;
            }
        });
        return words;
    }
}
