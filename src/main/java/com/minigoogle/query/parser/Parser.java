package com.minigoogle.query.parser;

import com.minigoogle.query.ast.*;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.lexer.TokenType;
import java.util.List;

/**
 * A simple recursive descent parser for search queries.
 * Precedence: NOT > AND > OR.
 *
 * <p>Adjacent terms with no operator between them are combined with the
 * {@linkplain ImplicitOperator implicit operator}. Explicit {@code AND},
 * {@code OR} and {@code NOT} always mean exactly what they say.</p>
 */
public class Parser {

    /** How adjacent terms with no operator between them are combined. */
    public enum ImplicitOperator {
        /**
         * Every term must be present. Correct for boolean filtering, and the
         * wrong default for natural-language search: a five-term question needs
         * one document containing all five terms, which on BEIR scifact returned
         * zero results for 299 of 300 queries.
         */
        AND,
        /**
         * Any term may match, with BM25 ranking deciding the order. This is the
         * standard bag-of-words retrieval model: a document matching more of the
         * query outranks one matching less, but partial matches still compete
         * instead of being discarded.
         */
        OR
    }

    /**
     * Adjacent terms are OR-ed by default. See {@link ImplicitOperator#OR}; the
     * previous AND default made realistic queries unsatisfiable.
     */
    public static final ImplicitOperator DEFAULT_IMPLICIT_OPERATOR = ImplicitOperator.OR;

    private final List<Token> tokens;
    private final ImplicitOperator implicitOperator;
    private int current;

    public Parser(List<Token> tokens) {
        this(tokens, DEFAULT_IMPLICIT_OPERATOR);
    }

    public Parser(List<Token> tokens, ImplicitOperator implicitOperator) {
        this.tokens = tokens;
        this.implicitOperator = implicitOperator;
        this.current = 0;
    }

    public QueryNode parse() {
        if (tokens.isEmpty()) {
            return null;
        }
        return expression();
    }

    private QueryNode expression() {
        return orExpression();
    }

    private QueryNode orExpression() {
        QueryNode node = andExpression();

        while (match(TokenType.OR)) {
            QueryNode right = andExpression();
            node = new OrNode(node, right);
        }

        return node;
    }

    private QueryNode andExpression() {
        QueryNode node = notExpression();

        while (true) {
            if (match(TokenType.AND)) {
                QueryNode right = notExpression();
                node = new AndNode(node, right);
            } else if (isNextImplicitAnd()) {
                // Adjacent terms with no operator between them.
                QueryNode right = notExpression();
                node = implicitOperator == ImplicitOperator.AND
                        ? new AndNode(node, right)
                        : new OrNode(node, right);
            } else {
                break;
            }
        }

        return node;
    }
    
    private boolean isNextImplicitAnd() {
        if (isAtEnd()) return false;
        TokenType type = peek().type();
        return type == TokenType.WORD || type == TokenType.PHRASE || type == TokenType.LEFT_PAREN || type == TokenType.NOT;
    }

    private QueryNode notExpression() {
        if (match(TokenType.NOT)) {
            QueryNode right = primary();
            return new NotNode(right);
        }
        return primary();
    }

    private QueryNode primary() {
        if (match(TokenType.LEFT_PAREN)) {
            QueryNode expr = expression();
            consume(TokenType.RIGHT_PAREN, "Expect ')' after expression.");
            return expr;
        }

        if (match(TokenType.PHRASE)) {
            return new PhraseNode(previous().value());
        }

        if (match(TokenType.WORD)) {
            return new WordNode(previous().value());
        }

        throw new ParseException("Expect term or phrase at token: " + peek().value());
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return current >= tokens.size();
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw new ParseException(message);
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }
}
