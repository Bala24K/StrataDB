package com.strata.executor;

import java.util.List;

/**
 * What running one {@link com.strata.parser.ast.Statement} produced. A sealed hierarchy, same
 * pattern as {@code parser.ast.Statement} — this gives a future caller (a REPL, Phase 12's
 * network server) an exhaustively {@code switch}-able result to print or act on.
 */
public sealed interface ExecutionResult {

    /** A {@code CREATE TABLE} succeeded. */
    record TableCreated(String tableName) implements ExecutionResult {}

    /** A {@code CREATE INDEX} succeeded. */
    record IndexCreated(String indexName) implements ExecutionResult {}

    /** An {@code INSERT} succeeded; {@code count} is always 1 (one statement, one row) for now. */
    record RowsInserted(int count) implements ExecutionResult {}

    /** An {@code UPDATE} succeeded; {@code count} is how many existing rows matched and changed. */
    record RowsUpdated(int count) implements ExecutionResult {}

    /** A {@code DELETE} succeeded; {@code count} is how many rows matched and were removed. */
    record RowsDeleted(int count) implements ExecutionResult {}

    /** A {@code SELECT}'s result set: column names (in the order requested) and the matching rows. */
    record Rows(List<String> columnNames, List<Row> rows) implements ExecutionResult {}

    /** A {@code BEGIN} started a new transaction. Only ever produced by {@code com.strata.transaction.Session}. */
    record TransactionStarted() implements ExecutionResult {}

    /** A {@code COMMIT} applied the current transaction's changes. Only ever produced by {@code Session}. */
    record TransactionCommitted() implements ExecutionResult {}

    /** A {@code ROLLBACK} discarded the current transaction's changes. Only ever produced by {@code Session}. */
    record TransactionRolledBack() implements ExecutionResult {}
}
