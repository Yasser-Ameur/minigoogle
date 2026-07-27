package com.minigoogle.query.ast;

/**
 * Root interface for the query AST (abstract syntax tree) node hierarchy.
 * All query components — words, phrases, and boolean operators — implement
 * this interface to enable visitor-based traversal.
 */
public interface QueryNode {
    <T> T accept(QueryVisitor<T> visitor);
}
