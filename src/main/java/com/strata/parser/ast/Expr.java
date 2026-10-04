package com.strata.parser.ast;

/**
 * Everything that can appear as a value or condition: literals, column references, and binary
 * operations (comparisons and {@code AND}/{@code OR}). This is a <b>sealed</b> hierarchy —
 * every possible kind of expression is declared right here, nested, in this one file.
 *
 * <p>That matters beyond organization: because the compiler knows the complete, closed set of
 * subtypes, any {@code switch} over an {@code Expr} (in the executor, later) can be written
 * exhaustively — the compiler will flag it as an error if a new expression kind is ever added
 * without updating every place that switches on one. That safety net is worth a lot in a
 * growing interpreter.
 *
 * <h2>Why {@code Literal} is its own nested sealed interface, not just part of {@code Expr}</h2>
 * An {@code INSERT ... VALUES (...)} list and an {@code UPDATE ... SET col = ...} assignment
 * are only ever allowed to contain literal values — not column references, not comparisons.
 * By typing those positions as {@code List<Expr.Literal>} / {@code Expr.Literal} instead of
 * plain {@code Expr}, that restriction is enforced by the Java compiler itself, for free —
 * there's no runtime "is this actually a literal?" check needed anywhere, because it's simply
 * not possible to construct an ill-typed AST in the first place.
 */
public sealed interface Expr {

    /** A constant value written directly in the SQL text. */
    sealed interface Literal extends Expr {
        record IntegerLiteral(long value) implements Literal {}
        record StringLiteral(String value) implements Literal {}
        record BooleanLiteral(boolean value) implements Literal {}
        record NullLiteral() implements Literal {}
    }

    /** A reference to a column by name — e.g. the {@code balance} in {@code balance > 100}. */
    record ColumnRef(String columnName) implements Expr {}

    /** A binary operation: a comparison ({@code =}, {@code <>}, ...) or a boolean combinator ({@code AND}/{@code OR}). */
    record Binary(Expr left, Operator operator, Expr right) implements Expr {}

    enum Operator {
        EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN, LESS_EQUALS, GREATER_EQUALS,
        AND, OR
    }
}
