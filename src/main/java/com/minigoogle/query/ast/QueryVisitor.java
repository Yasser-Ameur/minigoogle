package com.minigoogle.query.ast;

/**
 * Visitor pattern contract for traversing query AST nodes.
 * Implementations define type-safe visit methods for each concrete
 * node type (words, phrases, AND, OR, NOT) and produce a result of type {@code T}.
 */
public interface QueryVisitor<T> {
    T visit(WordNode node);
    T visit(PhraseNode node);
    T visit(AndNode node);
    T visit(OrNode node);
    T visit(NotNode node);
}
