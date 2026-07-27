package com.minigoogle.query.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer that splits query strings into typed tokens.
 * Recognizes words, double-quoted phrases, boolean operators (AND, OR, NOT),
 * and parentheses for grouping.
 */
public class Lexer {
    
    public List<Token> tokenize(String query) {
        List<Token> tokens = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return tokens;
        }

        int i = 0;
        int n = query.length();
        
        while (i < n) {
            char c = query.charAt(i);
            
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            
            if (c == '(') {
                tokens.add(new Token(TokenType.LEFT_PAREN, "("));
                i++;
                continue;
            }
            
            if (c == ')') {
                tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                i++;
                continue;
            }
            
            if (c == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n && query.charAt(i) != '"') {
                    sb.append(query.charAt(i));
                    i++;
                }
                if (i < n && query.charAt(i) == '"') {
                    i++; // skip closing quote
                }
                tokens.add(new Token(TokenType.PHRASE, sb.toString()));
                continue;
            }
            
            // Read a word
            StringBuilder sb = new StringBuilder();
            while (i < n && !Character.isWhitespace(query.charAt(i)) && query.charAt(i) != '(' && query.charAt(i) != ')' && query.charAt(i) != '"') {
                sb.append(query.charAt(i));
                i++;
            }
            
            String word = sb.toString();
            switch (word) {
                case "AND":
                    tokens.add(new Token(TokenType.AND, word));
                    break;
                case "OR":
                    tokens.add(new Token(TokenType.OR, word));
                    break;
                case "NOT":
                    tokens.add(new Token(TokenType.NOT, word));
                    break;
                default:
                    tokens.add(new Token(TokenType.WORD, word));
                    break;
            }
        }
        
        return tokens;
    }
}
