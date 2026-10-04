# Roadmap

Status legend: ✅ done · 🚧 in progress · ⏳ not started

| Phase | Name              | Deliverable                                              | Status |
|-------|-------------------|-----------------------------------------------------------|--------|
| 0     | Storage Fundamentals | Read/write raw binary pages. Nothing else.              | ✅     |
| 1     | Storage Engine    | `insert()` / `read()` / `delete()` records inside pages, no SQL | ✅     |
| 2     | Catalog           | System catalog: tables, columns, types, indexes           | ✅     |
| 3     | SQL Lexer         | SQL text → tokens                                          | ✅     |
| 4     | SQL Parser        | Tokens → AST (CREATE TABLE, INSERT, UPDATE, DELETE, SELECT) | ✅     |
| 5     | Query Executor    | AST → plan → rows (WHERE, ORDER BY, LIMIT; no joins yet)  | ✅     |
| 6     | Indexes           | Hash index, then B+Tree (search/insert/delete/split/merge) | 🚧     |
| 7     | Transactions      | BEGIN / COMMIT / ROLLBACK; ACID                             | ✅     |
| 8     | Lock Manager      | Shared/exclusive locks; safe concurrent access              | ✅     |
| 9     | Buffer Pool       | In-RAM page cache, LRU eviction, dirty-page tracking        | ✅     |
| 10    | Query Optimizer   | Logical plan → cost estimation → physical plan              | ✅     |
| 11    | Recovery          | Write-ahead log; crash → replay → recover                   | ✅     |
| 12    | Networking        | TCP server; multiple concurrent clients                     | ✅     |
| 13    | Authentication    | Users, roles, permissions, passwords                        | ⏳     |
| 14    | Replication       | Leader/follower data copying                                 | ⏳     |
| 15    | Distributed DB    | Sharding, replication, consensus                              | ⏳     |

Phases 12+ (networking, security, replication, distribution) and the later Docker/cloud/ML
work only start once the embedded, single-process core (through Phase 11) is solid — see the
original planning notes for the full "after the core database" list.

## Release milestones

Each milestone below is meant to be a tagged, working release — not a finished database.

| Tag  | Name                     | Roughly corresponds to |
|------|--------------------------|-------------------------|
| v0.1 | Binary Storage           | Phase 0                |
| v0.2 | Page Manager             | Phase 1                |
| v0.3 | SQL Parser               | Phases 2–4              |
| v0.4 | Query Executor           | Phase 5                 |
| v0.5 | Hash Index               | Phase 6 (hash part)     |
| v0.6 | Transactions             | Phase 7                 |
| v0.7 | Buffer Pool              | Phase 9                 |
| v0.8 | Recovery                 | Phase 11                |
| v0.9 | Network Server           | Phase 12                |
| v1.0 | Stable Embedded Database | Phases 13 and stabilization |

## Log

- **Phase 0 — done.** Implemented `com.strata.storage.page.Page` (fixed 4096-byte block with
  typed get/put at explicit offsets) and `com.strata.storage.page.DiskManager` (owns the
  database file; converts page id ↔ byte offset; `allocatePage` / `readPage` / `writePage` via
  `FileChannel`). 13 unit tests covering round-trips, bounds checking, page isolation, and
  persistence across a simulated restart — all passing. Write-up:
  [`docs/phase0-storage-fundamentals.md`](phase0-storage-fundamentals.md).

- **Phase 1 — done.** Implemented `com.strata.storage.SlottedPage` (header + growing slot
  directory + growing record-data area on a single page), `com.strata.storage.RecordId` (the
  stable `(pageId, slotIndex)` address type), and `com.strata.storage.HeapTable` (`insert` /
  `read` / `delete` / `scanAll` across a singly linked chain of slotted pages — a classic heap
  file). 18 new unit tests (31 total) covering round trips, free-space accounting, tombstoned
  deletes, bounds checking, multi-page spillover, restart persistence, and the
  larger-than-a-page rejection case — all passing. Write-up:
  [`docs/phase1-storage-engine.md`](phase1-storage-engine.md).

- **Phase 2 — done.** Implemented `com.strata.catalog.DataType` / `ColumnDefinition` /
  `TableInfo` (a table's schema, with a sequential binary (de)serialization format) and
  `com.strata.catalog.Catalog` (the system catalog itself, implemented as a `HeapTable` of
  `TableInfo` records, bootstrapped from a well-known root page — page 0 — in every database
  file). 13 new unit tests (44 total) covering the serialization round trip, bootstrap/open
  guard rails, duplicate-name and empty-schema rejection, `openTable` working end-to-end, and
  catalog + row data both surviving a simulated restart — all passing. Write-up:
  [`docs/phase2-system-catalog.md`](phase2-system-catalog.md).

- **Phase 3 — done.** Implemented `com.strata.lexer.TokenType` / `Token` / `LexException` and
  `com.strata.lexer.Lexer` (a hand-written scanner: two-pointer source scanning, keywords
  classified after the fact from the same identifier-scanning path, string-literal escape
  handling, and line/column tracking for error messages). 20 new unit tests (64 total),
  including the exact `SELECT * FROM Student;` example from the original project plan,
  case-insensitive keywords, comparison operators, comment skipping, and precise position
  tracking across multiple lines — all passing. Write-up:
  [`docs/phase3-sql-lexer.md`](phase3-sql-lexer.md).

- **Phase 4 — done.** Implemented `com.strata.parser.ast.Statement` / `Expr` / `ColumnDef` /
  `ColumnType` (a sealed-interface-and-record AST covering `CREATE TABLE`, `INSERT`, `UPDATE`,
  `DELETE`, and `SELECT` with `WHERE`/`ORDER BY`/`LIMIT`) and `com.strata.parser.Parser` (a
  hand-written recursive-descent parser where operator precedence for `AND`/`OR` falls directly
  out of the call structure, with no explicit precedence table). 24 new unit tests (88 total)
  covering every statement kind, precedence and parentheses, and parse-error cases — all
  passing. Write-up: [`docs/phase4-sql-parser.md`](phase4-sql-parser.md).

- **Phase 5 — done.** Implemented `com.strata.executor.Row` / `RowSerializer` (giving storage
  a real schema-aware record layout for the first time) / `ExecutionResult` /
  `ExecutionException`, and `com.strata.executor.Executor` (running `CREATE TABLE`, `INSERT`,
  `UPDATE`, `DELETE`, and `SELECT` — with `WHERE` evaluated using SQL's actual three-valued
  NULL logic, plus `ORDER BY` and `LIMIT`). Extended `HeapTable` with `scanAllWithIds()` so
  `UPDATE`/`DELETE` can act on specific matched rows. 33 new unit tests (121 total), including
  full end-to-end SQL workflows and persistence across a restart, caught one real bug along the
  way (`UPDATE ... SET` of an unknown column wasn't validated against an empty table) — all
  passing now. Write-up: [`docs/phase5-query-executor.md`](phase5-query-executor.md).

- **Phase 6 — in progress (hash index done, B+Tree not started).** Implemented
  `com.strata.index.hash.HashIndex` and `EntryCodec`: a persistent static hash index over
  `(key, RecordId)` pairs, built by reusing `HeapTable` directly as each bucket's storage (so
  overflow chaining past a bucket's first page comes for free from Phase 1's page-linking), with
  a contiguous-page "directory" so the whole index reopens from just two integers. 12 new unit
  tests (133 total), including duplicate keys, bucket collisions, overflow past one page, and a
  specific test pinning down a real hashing-consistency trap (an `Integer` and a `Long` boxing
  the same number must hash to the same bucket). Explicitly not yet done, and tracked as open
  work within this phase: dynamic bucket splitting/merging, the B+Tree, and any wiring into the
  catalog, SQL (`CREATE INDEX`), or the executor — a full table scan is still what every query
  uses, index or not; see the write-up for why that's deliberately deferred to Phase 10. Write-up:
  [`docs/phase6-hash-index.md`](phase6-hash-index.md).

- **Phase 7 — done.** Extracted `com.strata.storage.page.PageStore` from `DiskManager` (a small
  refactor with zero test changes needed, confirming the interface boundary was already in the
  right place) and implemented `com.strata.transaction.Transaction` — a `PageStore` that buffers
  writes in memory and applies them atomically on `commit()`, or discards them on `rollback()` —
  plus `com.strata.transaction.Session`, which routes statements to an explicit transaction or a
  fresh autocommit one. Added `BEGIN`/`COMMIT`/`ROLLBACK` to the lexer, parser, and AST; `Executor`
  deliberately refuses to run these three itself (a session-level concern, not an execution-level
  one). 27 new unit tests (160 total), including using `HeapTable`/`Catalog` directly through a
  `Transaction` with zero changes to either class, and a full simulated account-transfer workflow
  — all passing. Durability comes for free from Phase 0's fsync-on-every-write; genuine
  crash-safety *during* commit itself is an honestly-documented gap, left for Phase 11 (Recovery/
  WAL). Write-up: [`docs/phase7-transactions.md`](phase7-transactions.md).

- **Phase 8 — done.** Implemented `com.strata.lock.LockManager` (shared/exclusive locks over
  named resources, built from `synchronized`/`wait`/`notifyAll`, strict two-phase locking,
  timeout-based deadlock handling) and wired it into `Session`, which now acquires the right
  table-level lock before running each statement and releases everything a transaction held at
  `COMMIT`/`ROLLBACK`. 14 new unit tests (174 total), including two genuinely multi-threaded
  tests (a real blocking-until-release scenario and an actual two-thread deadlock resolved by
  timeout, both coordinated with `CountDownLatch`es for deterministic interleaving rather than
  timing guesses) and a multi-session SQL-level demonstration of concurrent readers and blocked
  writers — all passing, and stable across repeated runs. Write-up:
  [`docs/phase8-lock-manager.md`](phase8-lock-manager.md).

- **Phase 9 — done.** Implemented `com.strata.buffer.BufferPool` — an in-memory page cache with
  LRU eviction (via `LinkedHashMap`'s access-order mode) and deferred, dirty-page write-back —
  and wired it into `Session`, with `Transaction`s now built on a shared `BufferPool` instead of
  the raw `DiskManager`. Preserving Phase 7's durability guarantee required an explicit
  flush-on-commit (the NO-STEAL/FORCE policy). Along the way, found and fixed a real, subtle
  bug: the buffer pool's cache reused live `Page` objects across reads, so code mutating a page
  in place (the pattern every earlier phase always used safely) could silently corrupt the cache
  even inside a transaction that was later rolled back — fixed with defensive copies
  (`Page.copy()`) on both sides of the cache boundary. 16 new unit tests (190 total), including
  two that reproduce the aliasing bug directly — all passing, stable across repeated runs.
  Write-up: [`docs/phase9-buffer-pool.md`](phase9-buffer-pool.md).

- **Phase 10 — done (joins remain out of scope).** Extended the catalog with `IndexInfo`
  (tagged alongside `TableInfo` in the same catalog table) and added `CREATE INDEX name ON
  table (column)` to the SQL grammar. Implemented `com.strata.optimizer.QueryPlanner` — a
  rule-based (not yet cost-based) planner that routes `SELECT`/`UPDATE`/`DELETE` through an
  index lookup instead of a full scan whenever `WHERE` has a usable equality on an indexed
  column, including inside an `AND` chain. Every write path (`INSERT`/`UPDATE`/`DELETE`) now
  maintains every index on its table, so index results stay correct as data changes. This is
  the first point where the Phase 6 hash index actually gets used by a query. 25 new unit tests
  (218 total). Real multi-table joins ("Nested Loop"/"Hash Join" from the original plan) remain
  unsupported — the SQL grammar has never had multi-table syntax, and adding it honestly is a
  phase's worth of work on its own; named directly as the clearest gap versus the original plan
  rather than glossed over. Write-up:
  [`docs/phase10-query-optimizer.md`](phase10-query-optimizer.md).

- **Phase 11 — done.** Implemented `com.strata.recovery.WriteAheadLog` — an append-only,
  length-prefixed, checksummed log of committed transactions' page writes — and wired it into
  `Transaction.commit()` (log durably, *then* apply) and `Session.open()` (recover — replaying
  any committed-but-not-yet-applied writes from a prior crash — before anything else touches the
  file). Closes the exact durability gap named honestly in both Phase 7 and Phase 9: a crash
  between a transaction's commit and the buffer pool actually finishing its flush to disk.
  Redo-only recovery is correct (not merely simpler) given Phase 9's NO-STEAL policy — nothing
  uncommitted ever reaches disk, so there's nothing to undo. 8 new unit tests (226 total),
  including one that builds the exact "crash between commit and flush" scenario directly and
  confirms recovery finishes the job, plus tests for a torn trailing record and a corrupted
  checksum. Write-up: [`docs/phase11-recovery.md`](phase11-recovery.md).

- **Phase 12 — done.** Implemented `com.strata.network.DatabaseServer`/`DatabaseClient`/
  `Protocol` — a thread-per-connection TCP server making one database reachable by multiple
  independent client processes, each connection becoming its own `Session` sharing the database's
  `BufferPool`/`LockManager`/`WriteAheadLog`, over a simple line-oriented text protocol.
  Found and fixed the most serious bug in the project so far, latent in `LockManager` since
  Phase 8: releasing the last lock on a resource removed its `LockState` from the map, but a
  thread already waiting on that object (captured once, at the top of `acquire()`) could still
  be silently granted the lock against the now-orphaned copy, while a fresh, independent
  `LockState` got created for the same resource name — meaning two transactions could each
  believe they held an exclusive lock on the same table. It took real, sustained multi-client
  contention (eight networked clients hammering one table at once) to surface — every earlier,
  lighter-weight concurrency test happened to pass despite it. Fixed by never removing a
  `LockState` once created. 10 new unit tests (236 total), including the one that found the bug
  and one proving data persists across a full server restart. Write-up:
  [`docs/phase12-networking.md`](phase12-networking.md).
