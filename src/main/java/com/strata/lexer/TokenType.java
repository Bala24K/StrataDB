package com.strata.lexer;

/**
 * Every kind of token StrataDB's lexer can produce. Grouped by category below purely for
 * readability — Java doesn't care, but a future reader scanning this file does.
 */
public enum TokenType {

    // ---- statement keywords ----
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE, CREATE, TABLE, INDEX, ON,
    BEGIN, COMMIT, ROLLBACK,

    // ---- clause / logical keywords ----
    AND, OR, NOT, ORDER, BY, LIMIT, ASC, DESC,

    // ---- literal keywords ----
    NULL, TRUE, FALSE,

    // ---- data type keywords (mirror com.strata.catalog.DataType) ----
    INTEGER, BIGINT, VARCHAR, BOOLEAN,

    // ---- identifiers and literals ----
    IDENTIFIER, INTEGER_LITERAL, STRING_LITERAL,

    // ---- symbols ----
    STAR, COMMA, SEMICOLON, LPAREN, RPAREN, DOT, MINUS,
    EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN, LESS_EQUALS, GREATER_EQUALS,

    // ---- end of input ----
    EOF
}
