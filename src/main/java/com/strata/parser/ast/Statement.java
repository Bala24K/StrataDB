package com.strata.parser.ast;

import java.util.List;
import java.util.Optional;

/**
 * Every SQL statement StrataDB's parser can produce, as a sealed hierarchy: exactly the five
 * kinds named in the project plan (CREATE TABLE, INSERT, UPDATE, DELETE, SELECT), nested here
 * together with the small supporting types each one needs. This file is meant to be readable
 * top to bottom as "here is the complete shape of every statement StrataDB understands."
 */
public sealed interface Statement {

    /** {@code CREATE TABLE name (col1 TYPE, col2 TYPE, ...)} */
    record CreateTable(String tableName, List<ColumnDef> columns) implements Statement {}

    /** {@code CREATE INDEX name ON table (column)} — see Phase 10 docs for how it's used by the executor. */
    record CreateIndex(String indexName, String tableName, String columnName) implements Statement {}

    /**
     * {@code INSERT INTO name [(col1, col2, ...)] VALUES (v1, v2, ...)}
     *
     * <p>{@code columnNames} is empty when the statement omitted an explicit column list, in
     * which case values are matched positionally against the table's schema — but the parser
     * doesn't know the schema, so it's not the one who decides that; it just records what was
     * (or wasn't) written. Resolving positional values against actual columns is later work,
     * once a {@code Catalog} is available (the executor, Phase 5).
     */
    record Insert(String tableName, Optional<List<String>> columnNames, List<Expr.Literal> values) implements Statement {}

    /** {@code UPDATE name SET col1 = v1, col2 = v2, ... [WHERE condition]} */
    record Update(String tableName, List<Assignment> assignments, Optional<Expr> where) implements Statement {}

    /** One {@code col = value} pair in an {@code UPDATE ... SET} clause. */
    record Assignment(String columnName, Expr.Literal value) {}

    /** {@code DELETE FROM name [WHERE condition]} — no WHERE clause means "delete every row". */
    record Delete(String tableName, Optional<Expr> where) implements Statement {}

    /** {@code SELECT items FROM name [WHERE condition] [ORDER BY ...] [LIMIT n]} */
    record Select(
        List<SelectItem> items,
        String tableName,
        Optional<Expr> where,
        List<OrderByItem> orderBy,
        Optional<Long> limit
    ) implements Statement {}

    /** {@code BEGIN} — starts an explicit transaction. See {@code com.strata.transaction.Session}. */
    record Begin() implements Statement {}

    /** {@code COMMIT} — ends the current transaction, applying every change it made. */
    record Commit() implements Statement {}

    /** {@code ROLLBACK} — ends the current transaction, discarding every change it made. */
    record Rollback() implements Statement {}

    /** One item in a SELECT's column list: either {@code *} or a specific column name. */
    sealed interface SelectItem {
        record Star() implements SelectItem {}
        record Column(String columnName) implements SelectItem {}
    }

    /** One {@code column [ASC|DESC]} entry in an {@code ORDER BY} clause. */
    record OrderByItem(String columnName, SortDirection direction) {}

    enum SortDirection { ASC, DESC }
}
