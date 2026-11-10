package com.strata.optimizer;

import com.strata.catalog.Catalog;
import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.catalog.IndexInfo;
import com.strata.catalog.TableInfo;
import com.strata.parser.ast.Expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides how a table access runs: a {@link ScanPlan.FullScan}, an {@link ScanPlan.IndexScan}
 * (equality lookup in any index), or an {@link ScanPlan.IndexRangeScan} (ordered range in a
 * B+Tree). Rule-based, not cost-based: with no statistics, "use an index whenever a predicate
 * can use one, preferring equality" is the standard first heuristic.
 *
 * <h2>What counts as usable</h2>
 * The {@code WHERE} clause is split into its top-level {@code AND} terms. A term qualifies if
 * it compares an indexed column with a literal of the column's own type ({@code col op lit} or
 * {@code lit op col}):
 * <ul>
 *   <li>{@code =} on any indexed column gives an {@code IndexScan};</li>
 *   <li>{@code <, <=, >, >=} on a B+Tree-indexed column contribute bounds; several terms on the
 *       same column combine into the tightest range ({@code id >= 10 AND id < 20}).</li>
 * </ul>
 * Preference: equality, then a range bounded on both sides, then a one-sided range, then a full
 * scan. {@code OR} isn't decomposed (it would need a union of lookups) and {@code <>} can't use
 * an index.
 *
 * <h2>The index only narrows; the WHERE clause still decides</h2>
 * The executor evaluates the entire original {@code WHERE} against every row an index returns.
 * So a plan is correct as long as it returns a superset of the matching rows; a looser range
 * costs time, never correctness.
 *
 * <h2>Type check</h2>
 * A literal of the wrong type (e.g. {@code varchar_col = 5}) doesn't use the index. Previously
 * the hash index silently returned no rows for it while a full scan raised a type error, so the
 * same query behaved differently depending on whether an index existed. Now such queries take
 * the full scan and fail the same way either way.
 */
public final class QueryPlanner {

    private final Catalog catalog;

    public QueryPlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    public ScanPlan planScan(String tableName, Optional<Expr> where) {
        if (where.isEmpty()) {
            return new ScanPlan.FullScan(tableName);
        }
        List<Comparison> comparisons = new ArrayList<>();
        for (Expr term : conjuncts(where.get())) {
            comparison(term).ifPresent(comparisons::add);
        }

        // 1. Equality on any index.
        for (Comparison c : comparisons) {
            if (c.op() == Expr.Operator.EQUALS) {
                Optional<IndexInfo> index = usableIndex(tableName, c);
                if (index.isPresent()) {
                    return new ScanPlan.IndexScan(tableName, index.get().indexName(), c.value());
                }
            }
        }

        // 2./3. Ranges on B+Tree columns: tightest bounds per column, prefer two-sided.
        Map<String, Bounds> boundsByColumn = new LinkedHashMap<>();
        for (Comparison c : comparisons) {
            if (c.op() == Expr.Operator.EQUALS || !(c.value() instanceof Long v)) continue;
            Optional<IndexInfo> index = usableIndex(tableName, c).filter(i -> i.kind() == IndexInfo.Kind.BTREE);
            if (index.isEmpty()) continue;
            boundsByColumn.computeIfAbsent(c.column(), col -> new Bounds(index.get().indexName())).tighten(c.op(), v);
        }
        Bounds best = null;
        for (Bounds b : boundsByColumn.values()) {
            if (best == null || (b.twoSided() && !best.twoSided())) best = b;
        }
        if (best != null) {
            return new ScanPlan.IndexRangeScan(tableName, best.indexName, best.low, best.lowInclusive, best.high, best.highInclusive);
        }
        return new ScanPlan.FullScan(tableName);
    }

    // ---- predicate analysis ----

    /** {@code column op value}, normalized so the column is on the left. */
    private record Comparison(String column, Expr.Operator op, Object value) {}

    private static List<Expr> conjuncts(Expr expr) {
        List<Expr> out = new ArrayList<>();
        if (expr instanceof Expr.Binary bin && bin.operator() == Expr.Operator.AND) {
            out.addAll(conjuncts(bin.left()));
            out.addAll(conjuncts(bin.right()));
        } else {
            out.add(expr);
        }
        return out;
    }

    private static Optional<Comparison> comparison(Expr expr) {
        if (!(expr instanceof Expr.Binary bin)) {
            return Optional.empty();
        }
        Expr.Operator op = bin.operator();
        if (op == Expr.Operator.AND || op == Expr.Operator.OR || op == Expr.Operator.NOT_EQUALS) {
            return Optional.empty();
        }
        if (bin.left() instanceof Expr.ColumnRef ref && bin.right() instanceof Expr.Literal lit) {
            return literalValue(lit).map(v -> new Comparison(ref.columnName(), op, v));
        }
        if (bin.left() instanceof Expr.Literal lit && bin.right() instanceof Expr.ColumnRef ref) {
            return literalValue(lit).map(v -> new Comparison(ref.columnName(), flip(op), v)); // 5 < id  ==  id > 5
        }
        return Optional.empty();
    }

    private static Expr.Operator flip(Expr.Operator op) {
        return switch (op) {
            case LESS_THAN -> Expr.Operator.GREATER_THAN;
            case GREATER_THAN -> Expr.Operator.LESS_THAN;
            case LESS_EQUALS -> Expr.Operator.GREATER_EQUALS;
            case GREATER_EQUALS -> Expr.Operator.LESS_EQUALS;
            default -> op;
        };
    }

    /** "col = NULL" (or any comparison with NULL) is UNKNOWN for every row; never worth an index. */
    private static Optional<Object> literalValue(Expr.Literal literal) {
        return Optional.ofNullable(switch (literal) {
            case Expr.Literal.IntegerLiteral lit -> lit.value();
            case Expr.Literal.StringLiteral lit -> lit.value();
            case Expr.Literal.BooleanLiteral lit -> lit.value();
            case Expr.Literal.NullLiteral ignored -> null;
        });
    }

    /** The index on {@code c.column}, if there is one and {@code c.value} has the column's type. */
    private Optional<IndexInfo> usableIndex(String tableName, Comparison c) {
        Optional<IndexInfo> index = catalog.findIndexForColumn(tableName, c.column());
        if (index.isEmpty()) {
            return Optional.empty();
        }
        Optional<DataType> type = catalog.getTable(tableName).flatMap(t -> columnType(t, c.column()));
        return type.isPresent() && typeMatches(type.get(), c.value()) ? index : Optional.empty();
    }

    private static Optional<DataType> columnType(TableInfo table, String column) {
        return table.getColumns().stream().filter(col -> col.getName().equals(column)).map(ColumnDefinition::getType).findFirst();
    }

    private static boolean typeMatches(DataType type, Object value) {
        return switch (type) {
            case INTEGER, BIGINT -> value instanceof Long;
            case VARCHAR -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    /** Accumulates the tightest [low, high] bounds for one column. */
    private static final class Bounds {
        final String indexName;
        Long low;
        boolean lowInclusive;
        Long high;
        boolean highInclusive;

        Bounds(String indexName) {
            this.indexName = indexName;
        }

        void tighten(Expr.Operator op, long v) {
            switch (op) {
                case GREATER_THAN -> lower(v, false);
                case GREATER_EQUALS -> lower(v, true);
                case LESS_THAN -> upper(v, false);
                case LESS_EQUALS -> upper(v, true);
                default -> { }
            }
        }

        private void lower(long v, boolean inclusive) {
            if (low == null || v > low || (v == low && !inclusive)) {
                low = v;
                lowInclusive = inclusive;
            }
        }

        private void upper(long v, boolean inclusive) {
            if (high == null || v < high || (v == high && !inclusive)) {
                high = v;
                highInclusive = inclusive;
            }
        }

        boolean twoSided() {
            return low != null && high != null;
        }
    }
}
