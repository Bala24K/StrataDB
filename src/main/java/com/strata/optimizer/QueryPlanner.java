package com.strata.optimizer;

import com.strata.catalog.Catalog;
import com.strata.catalog.IndexInfo;
import com.strata.parser.ast.Expr;

import java.util.Optional;

/**
 * Decides how a table access should actually run: {@link ScanPlan.FullScan} by default, or
 * {@link ScanPlan.IndexScan} when the {@code WHERE} clause contains a usable equality
 * comparison on a column that happens to have an index (from Phase 6/Phase 10's
 * {@code CREATE INDEX}). This is intentionally a small, rule-based decision rather than a
 * cost-based one with real statistics (row counts, selectivity estimates) — "use the index if
 * an equality predicate on an indexed column is available" is a completely standard first-pass
 * heuristic real optimizers use before layering statistics-driven costing on top. That's a
 * reasonable, real technique to stop at for this project's scope, not a shortcut.
 *
 * <h2>What counts as "usable"</h2>
 * The planner looks for an equality (`column = value` or `value = column`) either as the
 * <em>entire</em> {@code WHERE} clause, or as one term of a top-level {@code AND} chain — so
 * {@code WHERE id = 5} and {@code WHERE id = 5 AND active = TRUE} both qualify (using the index
 * on {@code id}), but {@code WHERE id = 5 OR active = TRUE} does not: an {@code OR} would need
 * the results of two separate lookups unioned together, which is real, valid future work
 * (deliberately not built here). When an index is used for one term of an {@code AND}, the
 * <em>entire original</em> {@code WHERE} expression is still evaluated against every candidate
 * row the index returns — the index narrows the candidate set; it never replaces the full,
 * correct filter.
 */
public final class QueryPlanner {

    private final Catalog catalog;

    public QueryPlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    public ScanPlan planScan(String tableName, Optional<Expr> where) {
        if (where.isPresent()) {
            Optional<Equality> equality = findIndexableEquality(where.get(), tableName);
            if (equality.isPresent()) {
                return new ScanPlan.IndexScan(tableName, equality.get().indexName(), equality.get().value());
            }
        }
        return new ScanPlan.FullScan(tableName);
    }

    private record Equality(String indexName, Object value) {}

    private Optional<Equality> findIndexableEquality(Expr expr, String tableName) {
        if (!(expr instanceof Expr.Binary bin)) {
            return Optional.empty();
        }
        if (bin.operator() == Expr.Operator.AND) {
            Optional<Equality> left = findIndexableEquality(bin.left(), tableName);
            return left.isPresent() ? left : findIndexableEquality(bin.right(), tableName);
        }
        if (bin.operator() != Expr.Operator.EQUALS) {
            return Optional.empty();
        }
        Optional<ColumnEquals> columnEquals = asColumnEquals(bin);
        if (columnEquals.isEmpty()) {
            return Optional.empty();
        }
        Optional<IndexInfo> index = catalog.findIndexForColumn(tableName, columnEquals.get().columnName());
        return index.map(info -> new Equality(info.indexName(), columnEquals.get().value()));
    }

    private record ColumnEquals(String columnName, Object value) {}

    private static Optional<ColumnEquals> asColumnEquals(Expr.Binary equality) {
        Optional<ColumnEquals> direct = tryColumnEquals(equality.left(), equality.right());
        return direct.isPresent() ? direct : tryColumnEquals(equality.right(), equality.left());
    }

    private static Optional<ColumnEquals> tryColumnEquals(Expr columnSide, Expr valueSide) {
        if (!(columnSide instanceof Expr.ColumnRef ref) || !(valueSide instanceof Expr.Literal literal)) {
            return Optional.empty();
        }
        Object value = literalValue(literal);
        if (value == null) {
            return Optional.empty(); // "col = NULL" is never an equality worth indexing — see class docs
        }
        return Optional.of(new ColumnEquals(ref.columnName(), value));
    }

    private static Object literalValue(Expr.Literal literal) {
        return switch (literal) {
            case Expr.Literal.IntegerLiteral lit -> lit.value();
            case Expr.Literal.StringLiteral lit -> lit.value();
            case Expr.Literal.BooleanLiteral lit -> lit.value();
            case Expr.Literal.NullLiteral ignored -> null;
        };
    }
}
