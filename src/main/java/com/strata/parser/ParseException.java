package com.strata.parser;

/** Thrown when the {@link Parser} encounters a token sequence that doesn't form valid SQL. */
public final class ParseException extends RuntimeException {

    private final int line;
    private final int column;

    public ParseException(String message, int line, int column) {
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
