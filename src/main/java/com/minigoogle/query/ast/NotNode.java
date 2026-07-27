package com.minigoogle.query.ast;

/** Unary NOT node in the query AST. */
public record NotNode(QueryNode operand) implements QueryNode {
    @Override
    public <T> T accept(QueryVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
