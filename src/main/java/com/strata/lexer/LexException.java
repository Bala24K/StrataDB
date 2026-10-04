package com.strata.lexer;

/** Thrown when the {@link Lexer} encounters text it cannot turn into a valid token. */
public final class LexException extends RuntimeException {

    private final int line;
    private final int column;

    public LexException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }
}
