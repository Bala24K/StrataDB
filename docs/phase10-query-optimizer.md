# Phase 10 — Query Optimizer

Goal for this phase: give the executor an actual *choice* for the first time — full scan or
index scan — and have it choose correctly. This is where the hash index built in Phase 6, and
explicitly left unused ever since, finally gets consulted by a real query.

## 1. Why this phase couldn't happen any earlier

Phase 6's docs were explicit about this: building an index and a query *using* that index are
different pieces of work, and using one requires an actual decision to make. Before this phase,
every `SELECT`/`UPDATE`/`DELETE` had exactly one possible execution strategy — read every row,
check it against `WHERE` — so there was nothing to plan. The moment an index can exist on a
column, there are two strategies, and something has to decide between them. That something is
`QueryPlanner`.

## 2. A rule-based optimizer, not a cost-based one — and why that's a real choice, not a shortcut

A textbook cost-based optimizer estimates the cost of each candidate plan (how many rows will
this touch, how selective is this predicate) using statistics, and picks the cheapest. This
phase's `QueryPlanner` does something simpler: if the `WHERE` clause contains a usable equality
on a column that happens to have an index, use it; otherwise, full scan. No row counts, no
selectivity estimates, no comparison of alternatives.

This isn't a placeholder for "real" optimization — it's a genuine, standard first pass. "Use the
index if the predicate is an equality on an indexed column" is exactly the kind of rule real
optimizers apply before (or instead of, for simple queries) reaching for statistics-driven
costing, because for an equality predicate against a hash index, there's essentially never a
case where scanning the whole table would be faster. Layering true cost-based selection on top
(useful once there are multiple indexes, or once range predicates and a B+Tree are both on the
table) is real, valuable future work — deliberately not required for this phase to be complete.

## 3. What counts as "usable" — and why `AND` is supported but `OR` isn't

`QueryPlanner.planScan` looks for an equality (`column = value`, in either order) either as the
entire `WHERE` clause, or as one term of a top-level `AND` chain. So both of these use the index
on `id`:

```sql
WHERE id = 5
WHERE id = 5 AND active = TRUE
```

But `WHERE id = 5 OR active = TRUE` does not — falling back to a full scan is deliberate and
correct here, not a missed optimization. An `AND` only ever *narrows* the set of rows that could
possibly match (a row must pass every term), so finding candidates via just one indexed term is
always safe: some non-matching rows might slip through as candidates, but every actual match is
guaranteed to be among them. `OR` doesn't have that property — a row could satisfy the second
condition while failing the first, so an index lookup on the first term alone would silently
miss real matches. Answering an `OR` correctly with an index would mean running two separate
lookups and unioning the results — a real, valid technique, just not one this phase builds.

**Crucially, using the index for one `AND` term never means trusting it exclusively.** In
`Executor.fetchCandidates`, an index scan only narrows which rows are worth reading — every
candidate it returns is still checked against the *entire original* `WHERE` expression via
`matches`, exactly as a full scan's candidates always were.
`testIndexScanCombinedWithAndStillAppliesFullFilter` in `ExecutorTest` checks this directly: a
row that matches the indexed term but fails the other `AND` term must still be excluded.

## 4. Extending the catalog to remember indexes, using the same trick twice

Phase 2 deferred index metadata entirely — there was nothing to remember yet. This phase adds
`IndexInfo` alongside `TableInfo`, and both now live in the *same* catalog `HeapTable`,
distinguished by a one-byte tag written before each record's own bytes. This is the same
bootstrapping trick from Phase 2 (the catalog is a table that describes itself) applied a second
time: rather than inventing a second well-known root page for index metadata, one bootstrap
object just learns to describe two kinds of thing. Real systems more commonly split this into
separate catalog relations (Postgres has distinct `pg_class` and `pg_index` tables); a single
tagged table is a reasonable simplification at this project's scale, named honestly as a
simplification rather than presented as how it "should" be done.

## 5. `CREATE INDEX` on a populated table: a clean split of responsibility

Building an index against a table that already has rows means reading and decoding every
existing row — and `Catalog` deliberately doesn't know how to do that. Decoding a row's raw
bytes into typed values is `RowSerializer`'s job, which lives in the executor package;
`Catalog` operates one layer below that, in terms of schemas and page ids, not typed row values.
So `Catalog.createIndex` only allocates the empty index structure and records its metadata;
`Executor.executeCreateIndex` is what actually scans the table's existing rows afterward and
populates the index — the same layering discipline (syntax/schema concerns stay below,
data-interpretation concerns stay in the executor) that's held since Phase 4.

## 6. Index maintenance: the unglamorous, essential half of this phase

An index that isn't kept in sync with its table is actively dangerous — worse than no index,
since it would silently return wrong results (missing rows, or rows that were actually deleted)
instead of merely being slow. So every write path was extended:

- `executeInsert` adds a new entry to every index on the table it just wrote to.
- `executeDelete` removes the corresponding entry from every index.
- `executeUpdate` (already delete-then-reinsert, from Phase 5) does both — removing the old
  row's entries and adding the new row's, which correctly handles the case where the *indexed
  column itself* changed value (`testUpdatedRowIsFoundByNewValueNotOldValue` checks exactly
  this: after `UPDATE users SET id = 99 WHERE id = 1`, searching for the old value `1` must find
  nothing, and searching for `99` must find the row).

This maintenance work happens unconditionally on every write, regardless of whether the planner
will ever end up choosing to use that index for a query — correctness can't be allowed to depend
on how a later, unrelated query happens to get planned.

## 7. What Phase 10 deliberately does not do

- **No joins.** The project plan named "Nested Loop" and "Hash Join" as concepts for this phase,
  but StrataDB's SQL grammar has never supported more than one table per statement (a scope
  decision made explicitly back in Phase 4, and never revisited). Adding real multi-table query
  support — grammar for `JOIN`/`ON`, an AST representation for it, and an executor algorithm to
  actually combine rows from two tables — is genuinely a phase's worth of work on its own, not
  something to fold in as an afterthought. It's named here as the clearest concrete gap in the
  project relative to the original plan, not glossed over.
- **No true cost-based selection.** Covered in section 2 — the rule used here is real and
  standard, but it's a first pass, not the final word.
- **No `DROP INDEX`**, matching Phase 2's "no `DROP TABLE`" scope limit for the same reason: not
  needed until something would actually exercise it.
- **At most one index per column.** `Catalog.findIndexForColumn` returns a single result; a
  column could in principle have multiple indexes (rare in practice, but real systems allow it).

## 8. What to look at in the code

- `src/main/java/com/strata/catalog/IndexInfo.java` — a persisted index's metadata.
- `src/main/java/com/strata/catalog/Catalog.java` — extended with tagged dual storage,
  `createIndex`, `findIndexForColumn`, `openIndex`.
- `src/main/java/com/strata/optimizer/ScanPlan.java` / `QueryPlanner.java` — the physical plan
  type and the rule that chooses between them.
- `src/main/java/com/strata/executor/Executor.java` — `executeCreateIndex`, the
  `maintainIndexesOn{Insert,Delete}` helpers wired into every write path, and `fetchCandidates`
  routing `SELECT`/`UPDATE`/`DELETE` through the planner instead of always scanning everything.
- `src/main/java/com/strata/parser/ast/Statement.java` / `Parser.java` — `CREATE INDEX name ON
  table (column)`, added this phase.
- `src/test/java/com/strata/optimizer/QueryPlannerTest.java` — 8 tests on the planning decision
  in isolation: equality detection, `AND`-chain traversal, and the `OR`/range/no-index/
  equals-NULL cases that correctly fall back to a full scan.
- `src/test/java/com/strata/executor/ExecutorTest.java` — 10 new tests proving index-backed
  queries return identical results to a full scan, that writes after index creation stay
  correctly indexed, and that an index scan combined with `AND` still applies the complete
  filter.
- `src/test/java/com/strata/catalog/CatalogTest.java` — 7 new tests covering index registration,
  lookup, and persistence across a restart.
