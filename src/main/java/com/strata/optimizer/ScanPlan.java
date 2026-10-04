package com.strata.optimizer;

/**
 * A physical plan for how a statement's matching rows will actually be found: read every row
 * ({@link FullScan}) or look a key up directly in an index ({@link IndexScan}). This is the
 * "Physical Plan" named in the original project plan — the first point in StrataDB where there's
 * ever more than one possible way to execute the same query.
 */
public sealed interface ScanPlan {

    /** Read every row in the table and filter in memory — the only option before Phase 6/10, and still the fallback. */
    record FullScan(String tableName) implements ScanPlan {}

    /** Look {@code key} up directly in {@code indexName}, then read only the matching row(s). */
    record IndexScan(String tableName, String indexName, Object key) implements ScanPlan {}
}
