package com.minigoogle.query.ast;

/** Binary AND node in the query AST. */
public record AndNode(QueryNode left, QueryNode right) implements QueryNode {
    @Override
    public <T> T accept(QueryVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
