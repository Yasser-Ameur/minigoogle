package com.minigoogle.query.ast;

/** Exact phrase match node in the query AST. */
public record PhraseNode(String phrase) implements QueryNode {
    @Override
    public <T> T accept(QueryVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
