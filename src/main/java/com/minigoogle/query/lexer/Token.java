package com.minigoogle.query.lexer;

/** Record pairing a {@link TokenType} with its string value. */
public record Token(TokenType type, String value) {
}
