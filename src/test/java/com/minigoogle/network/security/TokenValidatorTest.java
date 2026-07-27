package com.minigoogle.network.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for TokenValidator security functionality. */
class TokenValidatorTest {

    @Test
    void testValidToken() {
        TokenValidator validator = new TokenValidator("secret123");
        assertTrue(validator.validate("Bearer secret123"));
    }

    @Test
    void testInvalidToken() {
        TokenValidator validator = new TokenValidator("secret123");
        assertFalse(validator.validate("Bearer wrongtoken"));
    }

    @Test
    void testNullHeader() {
        TokenValidator validator = new TokenValidator("secret123");
        assertFalse(validator.validate(null));
    }

    @Test
    void testEmptyHeader() {
        TokenValidator validator = new TokenValidator("secret123");
        assertFalse(validator.validate(""));
    }

    @Test
    void testNoBearerPrefix() {
        TokenValidator validator = new TokenValidator("secret123");
        assertFalse(validator.validate("secret123"));
    }

    @Test
    void testInvalidConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new TokenValidator(null));
        assertThrows(IllegalArgumentException.class, () -> new TokenValidator(""));
    }
}
