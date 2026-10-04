package com.strata.executor;

/**
 * Thrown for semantic errors the executor discovers while running a statement — an unknown
 * table or column, a value that doesn't fit its column's type, or the wrong number of values.
 * Unlike {@code LexException}/{@code ParseException} from earlier phases, there's no source
 * position to attach here — by execution time there's no notion of "line and column" anymore,
 * only "this statement, applied to this schema, doesn't make sense."
 *
 * <p>Not every error the executor can produce is wrapped in this type — errors the catalog
 * already reports clearly (like creating a table that already exists) are left to propagate
 * as-is rather than being re-wrapped for no benefit. This type covers the checks that are
 * genuinely new at this layer: resolving column names, and matching literal values against
 * column types.
 */
public final class ExecutionException extends RuntimeException {

    public ExecutionException(String message) {
        super(message);
    }
}
