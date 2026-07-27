package com.minigoogle.query.ast;

/** Single word token node in the query AST. */
public record WordNode(String word) implements QueryNode {
    @Override
    public <T> T accept(QueryVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
