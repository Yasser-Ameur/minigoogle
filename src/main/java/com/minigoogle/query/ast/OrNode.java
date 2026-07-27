package com.minigoogle.query.ast;

/** Binary OR node in the query AST. */
public record OrNode(QueryNode left, QueryNode right) implements QueryNode {
    @Override
    public <T> T accept(QueryVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
