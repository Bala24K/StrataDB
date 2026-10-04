package com.strata.lexer;

import java.util.Objects;

/**
 * One token produced by the {@link Lexer}: its {@link TokenType}, the exact slice of source
 * text it came from ("lexeme"), an optional already-parsed value for literals, and where in
 * the source it started (for error messages later, in the parser and beyond).
 *
 * <p><b>{@code literal} vs. {@code text}:</b> {@code text} is always the raw source
 * characters — e.g. an {@code INTEGER_LITERAL} for the source {@code "042"} has
 * {@code text = "042"}. {@code literal} is the already-interpreted value the lexer computed
 * from that text — {@code 42L} for that same token, or the unescaped contents of a string
 * literal with its surrounding quotes and doubled-quote escapes already resolved. Every other
 * token type (keywords, symbols, identifiers) leaves {@code literal} as {@code null} — there's
 * nothing to interpret beyond the text itself.
 *
 * <p><b>{@code equals}/{@code hashCode} intentionally ignore position.</b> Two tokens with the
 * same type, text, and literal are considered equal regardless of where they appeared in the
 * source. This is purely a testing convenience — it lets a test compare a lexer's output
 * against an expected token sequence without hard-coding line/column numbers everywhere.
 */
public final class Token {

    private final TokenType type;
    private final String text;
    private final Object literal;
    private final int line;
    private final int column;

    public Token(TokenType type, String text, Object literal, int line, int column) {
        this.type = type;
        this.text = text;
        this.literal = literal;
        this.line = line;
        this.column = column;
    }

    public TokenType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public Object getLiteral() {
        return literal;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Token other)) return false;
        return type == other.type && text.equals(other.text) && Objects.equals(literal, other.literal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, text, literal);
    }

    @Override
    public String toString() {
        return type + "('" + text + "')" + " at " + line + ":" + column;
    }
}
