package com.minigoogle.query.parser;

import com.minigoogle.query.ast.*;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.lexer.TokenType;
import java.util.List;

/**
 * A simple recursive descent parser for search queries.
 * Precedence: NOT > AND > OR.
 * Implicit AND is supported (e.g. "java compiler" -> "java AND compiler")
 */
public class Parser {
    private final List<Token> tokens;
    private int current;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
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
                // Implicit AND for adjacent words without operators
                QueryNode right = notExpression();
                node = new AndNode(node, right);
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
