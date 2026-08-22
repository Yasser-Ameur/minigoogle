package com.minigoogle.query.lexer;

import com.minigoogle.indexer.tokenizer.Tokenizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer that splits query strings into typed tokens.
 * Recognizes words, double-quoted phrases, boolean operators (AND, OR, NOT),
 * and parentheses for grouping.
 */
public class Lexer {

    private final Tokenizer wordTokenizer = new Tokenizer();
    
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
                // Splitting words on non-alphanumerics can empty a group that
                // held only punctuation ("alpha (+)- thalassemia"). An empty
                // group is not a query the parser accepts, so drop the pair
                // rather than emit one.
                if (!tokens.isEmpty()
                        && tokens.get(tokens.size() - 1).type() == TokenType.LEFT_PAREN) {
                    tokens.remove(tokens.size() - 1);
                } else {
                    tokens.add(new Token(TokenType.RIGHT_PAREN, ")"));
                }
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
                    // Query analysis must match index analysis. The indexer
                    // delimits on every non-alphanumeric character, so a
                    // document containing "COVID-19" is indexed under the two
                    // terms "covid" and "19" and the dictionary never holds
                    // "covid-19". Emitting the raw run as a single WORD makes
                    // that lookup miss silently, which drops the most
                    // discriminating term in the query out of both matching and
                    // BM25 scoring. Split with the indexer's own tokenizer so
                    // the two sides cannot drift apart.
                    for (String part : wordTokenizer.tokenize(word)) {
                        tokens.add(new Token(TokenType.WORD, part));
                    }
                    break;
            }
        }
        
        return tokens;
    }
}
