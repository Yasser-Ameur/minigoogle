package com.minigoogle.query.parser;

import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;

import java.util.List;

/**
 * Facade for constructing an Abstract Syntax Tree from a raw query string.
 *
 * Combines lexical analysis (Lexer) and recursive-descent parsing (Parser)
 * into a single convenient entry point.
 *
 * Usage:
 *   QueryNode tree = ASTBuilder.build("(java OR python) AND compiler");
 */
public class ASTBuilder {

    private ASTBuilder() {
    }

    /**
     * Lexes and parses a query string into an AST.
     *
     * @param query The raw query string.
     * @return The root node of the Abstract Syntax Tree, or null if the
     *         query is empty or blank.
     * @throws Parser.ParseException if the query contains syntax errors.
     */
    public static QueryNode build(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize(query);
        if (tokens.isEmpty()) {
            return null;
        }
        Parser parser = new Parser(tokens);
        return parser.parse();
    }
}
