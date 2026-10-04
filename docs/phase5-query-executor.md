# Phase 5 — Query Executor

Goal for this phase: actually *run* a parsed statement. This is where every module built so
far — storage, catalog, lexer, parser — finally meets in one place, and StrataDB executes real
SQL for the first time: `CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`, and `SELECT` with `WHERE`,
`ORDER BY`, and `LIMIT`.

## 1. Why there's no separate "Query Planner" yet

The architecture diagram (`docs/ARCHITECTURE.md`) has a box for a Query Planner sitting between
the parser and the executor. This phase doesn't build one — `Executor.execute()` goes straight
from AST to results. That's not a shortcut; it's honest about what planning actually *is*: a
planner's job is choosing between multiple ways to answer the same query (which index to use,
which order to join tables in). Without joins or indexes, there is only ever **one** possible
way to run any of these five statements — scan every row in the table, filter it, and (for
`SELECT`) sort and limit the results in memory. There's nothing to plan yet. A real planner earns
its keep starting with Phase 6 (indexes give the executor an actual choice: scan the table, or
use an index?) and really comes into its own at Phase 10 (the cost-based optimizer). For now,
`execute()` *is* the plan.

## 2. Record Layout, finally made concrete

Back in Phase 1, `HeapTable` stored opaque `byte[]` records — the docs there explicitly called
out that "Record Layout" (a phrase from the original project plan) wasn't implemented yet,
because there was no schema to give those bytes meaning. Now that `Catalog`/`TableInfo` exist,
`RowSerializer` finally closes that gap: it turns a schema-typed `Row` (a list of `Integer`/
`Long`/`String`/`Boolean`/`null` values, one per column) into bytes and back, using the same
sequential, self-describing binary approach `TableInfo` used for its own encoding in Phase 2 —
for each column, in schema order, a null flag followed by the value if it isn't null.

One deliberate simplification: null flags are one whole byte per column, not packed into a
bitmap the way a production system typically would. That costs a handful of extra bytes per row
and doesn't matter at this project's scale; it's a reasonable thing to optimize later; it isn't
one now.

## 3. Where syntax finally becomes semantics: `ColumnType` → `DataType`

Phase 4's docs promised that translating the parser's catalog-independent `ColumnType` into the
catalog's real `DataType` would happen "at the one point where the two worlds need to meet." This
is that point: `Executor.toDataType()`, called only inside `executeCreateTable`. Everywhere else
in the parser, `ColumnType` and `DataType` never touch.

## 4. `WHERE`, and SQL's three-valued logic

This is the trickiest correctness detail in the whole phase, and worth understanding properly:
in SQL, `NULL` isn't a value a comparison can be true or false *about*. `NULL = 5` isn't false —
it's **unknown**, because you don't know what the missing value actually is, so you can't say
whether it equals 5. A row only ends up in a `SELECT`'s results (or gets touched by `UPDATE`/
`DELETE`) if its `WHERE` clause evaluates to definite **TRUE** — not FALSE, and not UNKNOWN.

`Executor.evaluate()` models this directly: every sub-expression evaluates to `Boolean.TRUE`,
`Boolean.FALSE`, or Java `null` (standing in for SQL's UNKNOWN) — Java's own nullable `Boolean`
maps onto SQL's three-valued logic almost perfectly. Comparisons against a NULL operand produce
`null` (UNKNOWN) rather than throwing or defaulting to false. `AND` and `OR` then follow SQL's
actual truth tables, not ordinary two-valued Boolean logic:

- `FALSE AND UNKNOWN` is **FALSE** — a known "no" on one side is enough to know the whole `AND`
  is false, no matter what the other side turns out to mean.
- `TRUE OR UNKNOWN` is **TRUE** — for the same reason, mirrored.
- Anything else involving an UNKNOWN operand (`TRUE AND UNKNOWN`, `FALSE OR UNKNOWN`,
  `UNKNOWN AND UNKNOWN`, ...) stays UNKNOWN.

`testFalseAndUnknownIsFalseNotUnknown` in `ExecutorTest` exercises exactly this: a row where
`active = FALSE` (known) and `balance > 0` (unknown, because `balance` is NULL) is correctly
excluded, because the whole expression resolves to FALSE — not because Java short-circuited
past the NULL, but because that's genuinely what SQL says `FALSE AND UNKNOWN` means.

## 5. `UPDATE` is "delete, then reinsert" — on purpose

`HeapTable` only ever exposes `insert`/`read`/`delete` — there's no "overwrite this record in
place" operation, because a new value (especially a `VARCHAR`) can easily be a different number
of bytes than what was there before, and a `SlottedPage`'s record-data area doesn't support
resizing a record that's already been written into it. So `executeUpdate` does what many real
storage engines effectively do under the hood: delete the old record and insert the new one as a
fresh record, with a brand-new `RecordId`. This is simple and correct for now. It does mean that
anything which might one day hold onto an old `RecordId` across an update — most notably, a
future index — would need to be told about the change; that's explicitly future work for Phase 6,
noted here rather than solved prematurely.

Getting the *specific* record to delete required one small addition to `HeapTable` itself:
`scanAllWithIds()`, returning each record paired with the `RecordId` it lives at, so `UPDATE` and
`DELETE` can act on exactly the rows a `WHERE` clause matched. `scanAll()` from Phase 1 still
exists and is now just implemented in terms of this new method, so the actual page-chain-walking
logic lives in exactly one place.

## 6. A concrete bug the test suite caught (and what it teaches)

While building this phase, `UPDATE users SET ghost = TRUE;` against a table with **zero rows**
didn't throw, even though `ghost` isn't a real column — a bug caught by
`testUpdateUnknownColumnThrows`, which deliberately doesn't insert any rows first. The first
version of `executeUpdate` only resolved each `SET` column's name to an index *inside* the
per-row loop — so with no rows to iterate, the invalid column name was never actually looked at.

The fix: resolve every `SET` column's index once, up front, before the loop even starts —
semantic checks like "does this column exist" shouldn't depend on how much data happens to be
in the table at the moment. This is worth internalizing as a general validation principle, not
just a one-off fix: a statement should generally be rejected for being ill-formed against its
declared schema, independent of whatever data currently happens to exist.

## 7. What Phase 5 deliberately does not do

- **No joins.** Every statement here touches exactly one table.
- **No aggregates** (`COUNT`, `SUM`, ...) or `GROUP BY`.
- **No `NOT`** in `WHERE` clauses, matching the parser's own scope from Phase 4.
- **No index usage.** Every `SELECT`/`UPDATE`/`DELETE` is a full table scan — there's no index
  to consult yet (Phase 6).
- **No transactions.** If the process crashes mid-`UPDATE`, between the `delete` and the
  `insert` of a row's new version, the row is simply gone — there's no atomicity guarantee yet.
  That's exactly what Phase 7 (Transactions) and Phase 11 (Recovery/WAL) exist to fix.

## 8. What to look at in the code

- `src/main/java/com/strata/executor/Row.java` — a schema-ordered list of runtime values.
- `src/main/java/com/strata/executor/RowSerializer.java` — `Row` ↔ `byte[]`, schema-aware.
- `src/main/java/com/strata/executor/ExecutionResult.java` — what running a statement produces,
  one sealed variant per statement kind.
- `src/main/java/com/strata/executor/ExecutionException.java` — semantic errors this layer
  introduces (unknown table/column, type mismatch, wrong value count).
- `src/main/java/com/strata/executor/Executor.java` — the whole pipeline: `CREATE TABLE`
  through the catalog, `INSERT`/`UPDATE`/`DELETE` through `HeapTable`, `SELECT` with
  three-valued `WHERE` filtering, `ORDER BY`, and `LIMIT`.
- `src/main/java/com/strata/storage/HeapTable.java` — extended this phase with
  `scanAllWithIds()`/`StoredRecord`, needed so `UPDATE`/`DELETE` can act on specific rows.
- `src/test/java/com/strata/executor/RowSerializerTest.java` and `ExecutorTest.java` — 33 new
  tests (121 total), including full end-to-end SQL workflows, three-valued NULL logic, operator
  precedence carried all the way through to execution, and persistence across a restart.
