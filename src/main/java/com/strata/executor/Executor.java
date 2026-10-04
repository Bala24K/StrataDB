package com.strata.executor;

import com.strata.catalog.Catalog;
import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.catalog.IndexInfo;
import com.strata.catalog.TableInfo;
import com.strata.index.hash.HashIndex;
import com.strata.optimizer.QueryPlanner;
import com.strata.optimizer.ScanPlan;
import com.strata.parser.ast.ColumnType;
import com.strata.parser.ast.Expr;
import com.strata.parser.ast.Statement;
import com.strata.storage.HeapTable;
import com.strata.storage.RecordId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs a parsed {@link Statement} against a {@link Catalog} and its tables' storage — the point
 * where every earlier phase finally meets. Since Phase 10, this includes a real
 * {@link QueryPlanner}: a {@code SELECT}/{@code UPDATE}/{@code DELETE} whose {@code WHERE}
 * clause has a usable equality on an indexed column runs as an index lookup instead of a full
 * table scan — the first point where StrataDB has ever had more than one way to answer the same
 * query, and the first point where it actually chooses.
 *
 * <h2>Where "syntax" becomes "semantics"</h2>
 * This is the first module that needs both {@code parser.ast} (what the user asked for) and
 * {@code catalog} (what actually exists) at once — and so it's the natural place for the
 * translation Phase 4's docs promised: {@link #toDataType} maps the parser's catalog-independent
 * {@link ColumnType} onto the real {@link DataType} the catalog understands.
 *
 * <h2>WHERE, in three-valued logic</h2>
 * SQL's {@code NULL} isn't a value that comparisons can be true or false about — {@code NULL = 5}
 * is neither true nor false, it's <em>unknown</em>. {@link #evaluate} models this directly:
 * every expression evaluates to {@code Boolean.TRUE}, {@code Boolean.FALSE}, or Java {@code null}
 * (standing in for SQL's UNKNOWN) — and only rows where the whole WHERE clause evaluates to
 * {@code Boolean.TRUE} are kept. {@code AND}/{@code OR} follow SQL's actual three-valued truth
 * tables (see {@link #and} and {@link #or}), not ordinary Boolean logic — notably,
 * {@code FALSE AND UNKNOWN} is {@code FALSE}, not {@code UNKNOWN}.
 *
 * <h2>Index maintenance: every write keeps every index in sync</h2>
 * An index that isn't kept up to date is worse than no index — it would silently return stale
 * or missing results. So {@link #executeInsert} inserts a matching entry into every index on
 * the table it just wrote to, {@link #executeDelete} removes the corresponding entry, and
 * {@link #executeUpdate} (already implemented as delete-then-reinsert, see the Phase 5 docs)
 * does both: remove the old row's entries, add the new row's. This is on the write path, not
 * conditional on whether the planner will ever end up using the index — correctness has to hold
 * regardless of how a later query happens to be planned.
 */
public final class Executor {

    private final Catalog catalog;
    private final QueryPlanner queryPlanner;

    public Executor(Catalog catalog) {
        this.catalog = catalog;
        this.queryPlanner = new QueryPlanner(catalog);
    }

    public ExecutionResult execute(Statement statement) throws IOException {
        return switch (statement) {
            case Statement.CreateTable stmt -> executeCreateTable(stmt);
            case Statement.CreateIndex stmt -> executeCreateIndex(stmt);
            case Statement.Insert stmt -> executeInsert(stmt);
            case Statement.Update stmt -> executeUpdate(stmt);
            case Statement.Delete stmt -> executeDelete(stmt);
            case Statement.Select stmt -> executeSelect(stmt);
            case Statement.Begin ignored -> throw new ExecutionException(
                "BEGIN must be handled by a Session, not run directly against an Executor");
            case Statement.Commit ignored -> throw new ExecutionException(
                "COMMIT must be handled by a Session, not run directly against an Executor");
            case Statement.Rollback ignored -> throw new ExecutionException(
                "ROLLBACK must be handled by a Session, not run directly against an Executor");
        };
    }

    // ---- CREATE TABLE ----

    private ExecutionResult.TableCreated executeCreateTable(Statement.CreateTable stmt) throws IOException {
        List<ColumnDefinition> columns = stmt.columns().stream()
            .map(col -> new ColumnDefinition(col.name(), toDataType(col.type())))
            .toList();
        catalog.createTable(stmt.tableName(), columns); // propagates IllegalArgumentException as-is; see class docs
        return new ExecutionResult.TableCreated(stmt.tableName());
    }

    private static DataType toDataType(ColumnType type) {
        return switch (type) {
            case INTEGER -> DataType.INTEGER;
            case BIGINT -> DataType.BIGINT;
            case VARCHAR -> DataType.VARCHAR;
            case BOOLEAN -> DataType.BOOLEAN;
        };
    }

    // ---- CREATE INDEX ----

    private static final int DEFAULT_INDEX_BUCKETS = 16;

    private ExecutionResult.IndexCreated executeCreateIndex(Statement.CreateIndex stmt) throws IOException {
        TableInfo schema = requireTable(stmt.tableName());
        int columnIdx = columnIndex(schema, stmt.columnName()); // validates the column before anything is allocated

        IndexInfo indexInfo = catalog.createIndex(stmt.indexName(), stmt.tableName(), stmt.columnName(), DEFAULT_INDEX_BUCKETS);

        // Catalog only allocates the empty index — populating it from whatever rows the table
        // already has is the executor's job (it's the one that knows how to decode a row's bytes).
        HashIndex index = catalog.openIndex(indexInfo);
        HeapTable table = catalog.openTable(stmt.tableName());
        for (HeapTable.StoredRecord stored : table.scanAllWithIds()) {
            Row row = RowSerializer.deserialize(stored.data(), schema);
            Object value = row.get(columnIdx);
            if (value != null) {
                index.insert(value, stored.id());
            }
        }

        return new ExecutionResult.IndexCreated(stmt.indexName());
    }

    // ---- INSERT ----

    private ExecutionResult.RowsInserted executeInsert(Statement.Insert stmt) throws IOException {
        TableInfo schema = requireTable(stmt.tableName());
        List<ColumnDefinition> columns = schema.getColumns();
        Object[] values = new Object[columns.size()]; // unmentioned columns default to null (NULL)

        if (stmt.columnNames().isPresent()) {
            List<String> names = stmt.columnNames().get();
            if (names.size() != stmt.values().size()) {
                throw new ExecutionException(
                    "Column list has " + names.size() + " name(s) but " + stmt.values().size() + " value(s) were given");
            }
            for (int i = 0; i < names.size(); i++) {
                int idx = columnIndex(schema, names.get(i));
                values[idx] = coerceLiteral(stmt.values().get(i), columns.get(idx).getType());
            }
        } else {
            if (stmt.values().size() != columns.size()) {
                throw new ExecutionException(
                    "Table '" + stmt.tableName() + "' has " + columns.size() + " column(s) "
                    + "but " + stmt.values().size() + " value(s) were given");
            }
            for (int i = 0; i < columns.size(); i++) {
                values[i] = coerceLiteral(stmt.values().get(i), columns.get(i).getType());
            }
        }

        Row row = new Row(Arrays.asList(values));
        HeapTable table = catalog.openTable(stmt.tableName());
        RecordId rid = table.insert(RowSerializer.serialize(row, schema));
        maintainIndexesOnInsert(schema, row, rid);
        return new ExecutionResult.RowsInserted(1);
    }

    // ---- UPDATE ----

    private ExecutionResult.RowsUpdated executeUpdate(Statement.Update stmt) throws IOException {
        TableInfo schema = requireTable(stmt.tableName());
        HeapTable table = catalog.openTable(stmt.tableName());

        // Resolve every SET column's index up front — an unknown column name must be rejected
        // even if the table currently has zero matching (or zero total) rows to iterate over.
        List<Integer> assignmentIndices = new ArrayList<>();
        for (Statement.Assignment assignment : stmt.assignments()) {
            assignmentIndices.add(columnIndex(schema, assignment.columnName()));
        }

        int updatedCount = 0;
        for (HeapTable.StoredRecord stored : fetchCandidates(stmt.tableName(), stmt.where(), table)) {
            Row row = RowSerializer.deserialize(stored.data(), schema);
            if (!matches(stmt.where(), row, schema)) {
                continue;
            }
            List<Object> newValues = new ArrayList<>(row.values());
            for (int i = 0; i < stmt.assignments().size(); i++) {
                int idx = assignmentIndices.get(i);
                Expr.Literal value = stmt.assignments().get(i).value();
                newValues.set(idx, coerceLiteral(value, schema.getColumns().get(idx).getType()));
            }
            Row newRow = new Row(newValues);

            maintainIndexesOnDelete(schema, row, stored.id());
            table.delete(stored.id());
            RecordId newRid = table.insert(RowSerializer.serialize(newRow, schema));
            maintainIndexesOnInsert(schema, newRow, newRid);
            updatedCount++;
        }
        return new ExecutionResult.RowsUpdated(updatedCount);
    }

    // ---- DELETE ----

    private ExecutionResult.RowsDeleted executeDelete(Statement.Delete stmt) throws IOException {
        TableInfo schema = requireTable(stmt.tableName());
        HeapTable table = catalog.openTable(stmt.tableName());

        int deletedCount = 0;
        for (HeapTable.StoredRecord stored : fetchCandidates(stmt.tableName(), stmt.where(), table)) {
            Row row = RowSerializer.deserialize(stored.data(), schema);
            if (matches(stmt.where(), row, schema)) {
                maintainIndexesOnDelete(schema, row, stored.id());
                table.delete(stored.id());
                deletedCount++;
            }
        }
        return new ExecutionResult.RowsDeleted(deletedCount);
    }

    // ---- SELECT ----

    private ExecutionResult.Rows executeSelect(Statement.Select stmt) throws IOException {
        TableInfo schema = requireTable(stmt.tableName());
        HeapTable table = catalog.openTable(stmt.tableName());

        List<Row> matched = new ArrayList<>();
        for (HeapTable.StoredRecord stored : fetchCandidates(stmt.tableName(), stmt.where(), table)) {
            Row row = RowSerializer.deserialize(stored.data(), schema);
            if (matches(stmt.where(), row, schema)) {
                matched.add(row);
            }
        }

        if (!stmt.orderBy().isEmpty()) {
            matched.sort(buildComparator(stmt.orderBy(), schema));
        }
        if (stmt.limit().isPresent()) {
            int limit = (int) Math.min(stmt.limit().get(), matched.size());
            matched = new ArrayList<>(matched.subList(0, limit));
        }

        return project(stmt.items(), matched, schema);
    }

    /** By grammar construction (see Parser), items is either exactly [Star] or all Columns — never mixed. */
    private ExecutionResult.Rows project(List<Statement.SelectItem> items, List<Row> rows, TableInfo schema) {
        if (items.get(0) instanceof Statement.SelectItem.Star) {
            List<String> columnNames = schema.getColumns().stream().map(ColumnDefinition::getName).toList();
            return new ExecutionResult.Rows(columnNames, rows);
        }

        List<Integer> indices = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();
        for (Statement.SelectItem item : items) {
            Statement.SelectItem.Column column = (Statement.SelectItem.Column) item;
            indices.add(columnIndex(schema, column.columnName()));
            columnNames.add(column.columnName());
        }

        List<Row> projected = rows.stream()
            .map(row -> new Row(indices.stream().map(row::get).toList()))
            .toList();
        return new ExecutionResult.Rows(columnNames, projected);
    }

    // ---- query planning: full scan vs. index scan (Phase 10) ----

    /**
     * Every row a {@code SELECT}/{@code UPDATE}/{@code DELETE} needs to *consider* — not
     * necessarily every row that ultimately matches. Callers still run the full {@code WHERE}
     * expression against each candidate afterward via {@link #matches}: an index scan only
     * narrows which rows are worth checking, it never replaces the check itself (see
     * {@link QueryPlanner}'s docs for why that matters once an {@code AND} is involved).
     */
    private List<HeapTable.StoredRecord> fetchCandidates(String tableName, Optional<Expr> where, HeapTable table) throws IOException {
        ScanPlan plan = queryPlanner.planScan(tableName, where);
        if (plan instanceof ScanPlan.IndexScan indexScan) {
            IndexInfo indexInfo = catalog.getIndexesForTable(tableName).stream()
                .filter(info -> info.indexName().equals(indexScan.indexName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Planner chose an index that no longer exists: " + indexScan.indexName()));
            HashIndex index = catalog.openIndex(indexInfo);
            List<HeapTable.StoredRecord> results = new ArrayList<>();
            for (RecordId rid : index.search(indexScan.key())) {
                results.add(new HeapTable.StoredRecord(rid, table.read(rid)));
            }
            return results;
        }
        return table.scanAllWithIds();
    }

    // ---- index maintenance (Phase 10) ----

    private void maintainIndexesOnInsert(TableInfo schema, Row row, RecordId rid) throws IOException {
        for (IndexInfo indexInfo : catalog.getIndexesForTable(schema.getName())) {
            Object value = row.get(columnIndex(schema, indexInfo.columnName()));
            if (value != null) {
                catalog.openIndex(indexInfo).insert(value, rid);
            }
        }
    }

    private void maintainIndexesOnDelete(TableInfo schema, Row row, RecordId rid) throws IOException {
        for (IndexInfo indexInfo : catalog.getIndexesForTable(schema.getName())) {
            Object value = row.get(columnIndex(schema, indexInfo.columnName()));
            if (value != null) {
                catalog.openIndex(indexInfo).delete(value, rid);
            }
        }
    }

    private static Comparator<Row> buildComparator(List<Statement.OrderByItem> orderBy, TableInfo schema) {
        Comparator<Row> comparator = null;
        for (Statement.OrderByItem item : orderBy) {
            int idx = columnIndex(schema, item.columnName());
            Comparator<Row> keyComparator = Comparator.comparing(row -> row.get(idx), Executor::compareForSort);
            if (item.direction() == Statement.SortDirection.DESC) {
                keyComparator = keyComparator.reversed();
            }
            comparator = (comparator == null) ? keyComparator : comparator.thenComparing(keyComparator);
        }
        return comparator;
    }

    // ---- WHERE evaluation: three-valued logic (see class docs) ----

    private static boolean matches(Optional<Expr> where, Row row, TableInfo schema) {
        if (where.isEmpty()) {
            return true;
        }
        return Boolean.TRUE.equals(evaluate(where.get(), row, schema));
    }

    private static Object evaluate(Expr expr, Row row, TableInfo schema) {
        return switch (expr) {
            case Expr.Literal literal -> literalValue(literal);
            case Expr.ColumnRef ref -> row.get(columnIndex(schema, ref.columnName()));
            case Expr.Binary bin -> evaluateBinary(bin, row, schema);
        };
    }

    private static Object literalValue(Expr.Literal literal) {
        return switch (literal) {
            case Expr.Literal.IntegerLiteral lit -> lit.value();
            case Expr.Literal.StringLiteral lit -> lit.value();
            case Expr.Literal.BooleanLiteral lit -> lit.value();
            case Expr.Literal.NullLiteral ignored -> null;
        };
    }

    private static Object evaluateBinary(Expr.Binary bin, Row row, TableInfo schema) {
        if (bin.operator() == Expr.Operator.AND) {
            return and(asBoolean(evaluate(bin.left(), row, schema)), asBoolean(evaluate(bin.right(), row, schema)));
        }
        if (bin.operator() == Expr.Operator.OR) {
            return or(asBoolean(evaluate(bin.left(), row, schema)), asBoolean(evaluate(bin.right(), row, schema)));
        }

        Object left = evaluate(bin.left(), row, schema);
        Object right = evaluate(bin.right(), row, schema);
        if (left == null || right == null) {
            return null; // comparing anything against NULL is UNKNOWN, not true or false
        }
        int cmp = compareValues(left, right);
        return switch (bin.operator()) {
            case EQUALS -> cmp == 0;
            case NOT_EQUALS -> cmp != 0;
            case LESS_THAN -> cmp < 0;
            case GREATER_THAN -> cmp > 0;
            case LESS_EQUALS -> cmp <= 0;
            case GREATER_EQUALS -> cmp >= 0;
            case AND, OR -> throw new IllegalStateException("unreachable: handled above");
        };
    }

    /** SQL's AND truth table: FALSE dominates (FALSE AND UNKNOWN = FALSE), otherwise UNKNOWN spreads. */
    private static Boolean and(Boolean a, Boolean b) {
        if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) return false;
        if (a == null || b == null) return null;
        return true;
    }

    /** SQL's OR truth table: TRUE dominates (TRUE OR UNKNOWN = TRUE), otherwise UNKNOWN spreads. */
    private static Boolean or(Boolean a, Boolean b) {
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return true;
        if (a == null || b == null) return null;
        return false;
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        throw new ExecutionException("AND/OR operands must be boolean expressions, but got " + describeValue(value));
    }

    // ---- value comparison, coercion, and column resolution ----

    /** Numeric values are compared after promoting to long, so an INTEGER column and a BIGINT column compare correctly. */
    private static int compareValues(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) {
            return Long.compare(l.longValue(), r.longValue());
        }
        if (left instanceof String l && right instanceof String r) {
            return l.compareTo(r);
        }
        if (left instanceof Boolean l && right instanceof Boolean r) {
            return Boolean.compare(l, r);
        }
        throw new ExecutionException("Cannot compare " + describeValue(left) + " with " + describeValue(right));
    }

    /** Like {@link #compareValues}, but treats NULL as sorting before every non-null value. */
    private static int compareForSort(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return compareValues(a, b);
    }

    private static String describeValue(Object value) {
        return value == null ? "NULL" : value + " (" + value.getClass().getSimpleName() + ")";
    }

    /** Turns a parsed literal into a runtime value matching {@code targetType}, or throws with a clear message. */
    private static Object coerceLiteral(Expr.Literal literal, DataType targetType) {
        if (literal instanceof Expr.Literal.NullLiteral) {
            return null;
        }
        return switch (targetType) {
            case INTEGER -> {
                if (!(literal instanceof Expr.Literal.IntegerLiteral lit)) {
                    throw new ExecutionException("Expected an INTEGER value but got " + describeLiteral(literal));
                }
                long value = lit.value();
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw new ExecutionException("Value " + value + " does not fit in an INTEGER column (try BIGINT)");
                }
                yield (int) value;
            }
            case BIGINT -> {
                if (!(literal instanceof Expr.Literal.IntegerLiteral lit)) {
                    throw new ExecutionException("Expected a BIGINT value but got " + describeLiteral(literal));
                }
                yield lit.value();
            }
            case VARCHAR -> {
                if (!(literal instanceof Expr.Literal.StringLiteral lit)) {
                    throw new ExecutionException("Expected a VARCHAR value but got " + describeLiteral(literal));
                }
                yield lit.value();
            }
            case BOOLEAN -> {
                if (!(literal instanceof Expr.Literal.BooleanLiteral lit)) {
                    throw new ExecutionException("Expected a BOOLEAN value but got " + describeLiteral(literal));
                }
                yield lit.value();
            }
        };
    }

    private static String describeLiteral(Expr.Literal literal) {
        return switch (literal) {
            case Expr.Literal.IntegerLiteral lit -> "a number (" + lit.value() + ")";
            case Expr.Literal.StringLiteral lit -> "a string ('" + lit.value() + "')";
            case Expr.Literal.BooleanLiteral lit -> "a boolean (" + lit.value() + ")";
            case Expr.Literal.NullLiteral ignored -> "NULL";
        };
    }

    private static int columnIndex(TableInfo schema, String columnName) {
        List<ColumnDefinition> columns = schema.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(columnName)) {
                return i;
            }
        }
        throw new ExecutionException("No such column: '" + columnName + "' in table '" + schema.getName() + "'");
    }

    private TableInfo requireTable(String name) {
        return catalog.getTable(name)
            .orElseThrow(() -> new ExecutionException("No such table: '" + name + "'"));
    }
}
