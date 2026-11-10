# Engineering audit and upgrade record (2026-11)

This file records the repository audit that started the engineering upgrade, what each finding
turned out to be, and what was done about it. Commit ids refer to the `upgrade/roadmap` branch.
Benchmark evidence is in [`BENCHMARKS.md`](BENCHMARKS.md); the resulting design is described in
[`ENGINE.md`](ENGINE.md).

## Corrections to the project's self-description

| Claimed | Found |
|---|---|
| Authentication, roles, permissions | Not implemented (Phase 13 never started). Still not implemented; see "Not done". |
| Automated tests | 236 tests existed, but `mvn test` ran **zero** of them: they use a home-made runner, not JUnit. |
| Cost-based optimizer | Rule-based: equality on an indexed column, else a full scan. |
| B+Tree | Not started. Implemented in T8. |

## Correctness findings

"Reproduced" means a program demonstrated the bug before the fix; each now has a regression test.

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Concurrent transactions were handed the **same new page id** (allocation counter private to each transaction). Cross-table data corruption; same-table autocommit inserts built a cyclic page chain and `HeapTable.insert` spun forever holding the table's X lock. | Critical, reproduced | Fixed in T1 (`5f6b323`): allocate from the shared store. `ConcurrencyRegressionTest`. |
| 2 | Catalog pages were outside the lock protocol: two concurrent `CREATE TABLE`s lost one table. | Critical, reproduced | Fixed in T2 (`0c6864a`): catalog lock (X for DDL, S otherwise). |
| 3 | Explicit transactions loaded the catalog at `BEGIN`, before locking: an index created afterwards was never maintained by their inserts. | Critical, reproduced | Fixed in T2: catalog loaded after the first catalog lock. |
| 4 | No statement-level atomicity: a failed `UPDATE` inside `BEGIN` deleted the row, and `COMMIT` kept that. | High, reproduced | Fixed in T3 (`d7d1a6b`): per-statement savepoints in `Transaction`. |
| 5 | A client disconnecting mid-transaction held its locks forever. | High, reproduced | Fixed in T3: sessions roll back on close; server always closes them. |
| 6 | A VARCHAR value `'END'` desynchronised the wire protocol. | Medium, reproduced | Fixed in T3 (framing by row count, escaping); `'NULL'` vs NULL ambiguity fixed in T11. |
| 7 | Garbage WAL length prefix made the database impossible to open; mid-log corruption silently dropped acknowledged commits. | Medium | Fixed in T4 (`5a81d32`), extended for group commit in T6 (durable-horizon field in WAL v2). |
| 8 | WAL log order and in-memory apply order could diverge. | Medium | Fixed in T4 (single critical section), then T6 (group commit with a runtime order check). |
| 9 | A failure halfway through applying a commit left a half-applied transaction visible. | Medium | Fixed in T4: fail-stop; recovery completes the commit on reopen. |
| 10 | The server reused a stale `.wal` beside a new database file. | Low | Fixed in T4: `Database` facade, one open/bootstrap/recover path. |
| 11 | Lock manager: reader-starved writers, upgrade deadlocks resolved only by timeout, non-monotonic clock. | Low | Fixed in T5a (monotonic clock) and T10 (FIFO queues, deadlock detection). |
| 12 | Index scan and full scan disagreed on type-mismatched predicates. | Low | Fixed in T8: the planner only uses an index for a literal of the column's type. |

## Performance findings → measured outcome

All numbers: medium scale, median of 3 runs, same laptop (details and caveats in
[`BENCHMARKS.md`](BENCHMARKS.md)). The final baseline-vs-final comparison was run interleaved
to cancel out machine drift; see "Final validation" in BENCHMARKS.md.

| Audit hypothesis | Evidence | Change | Result |
|---|---|---|---|
| Commits pay redundant fsyncs (FORCE + WAL) | 2.0 fsyncs per autocommit insert, 5.0 per indexed update | T6: NO-FORCE, group commit, checkpoints | 1.0 fsync/commit; 0.26 with 8 concurrent writers |
| Commits serialize on one lock | 8 threads: 709 ops/s vs 550 for 1 | T6: group commit | 8 threads on separate tables scale (4.2k ops/s) |
| Insert walks the whole page chain | 663 KB allocated per bulk-inserted row | T7: tail-page hint | 7 KB/row; bulk insert 9.6k → ~150k rows/s |
| UPDATE rewrites every index entry | 16 KB WAL per indexed update | T7: in-place update | 4 KB WAL per update |
| Static 16-bucket hash index degrades linearly | JMH: 388 µs lookup at N = 100k | T8: B+Tree (default for numeric columns) | 2.8 µs at N = 100k |
| No range access path | Range query = full scan, 180/s | T8: B+Tree range scans + planner | ~8–17k/s |
| Everything materialized before filtering | 15 MB allocated per unindexed lookup | T9: push pipeline, in-place decoding | 6 MB; full scan 3.4M → 12.6M rows/s |
| Unfair lock wake-ups | 8 writers, one table: p99 108–130 ms, max 0.7 s | T10: FIFO queues | p99 11–15 ms, max 12–17 ms (median rises; deliberate trade) |
| Text protocol overhead | — | T11: measured | 0.4 µs of a 17 µs round trip: binary protocol not justified |
| `Page.getInt` allocates a `ByteBuffer` per call | JMH: 0 bytes allocated | none | Escape analysis already removes it; a non-problem found by measuring |

## Decisions recorded along the way

- **Correctness before performance, and benchmarks only after concurrency was correct.**
  Measuring concurrent throughput of an engine that corrupts data under concurrency would have
  produced meaningless numbers, so T1–T4 came before the baseline.
- **Page ids are allocated eagerly from the shared store**; a rolled-back transaction leaks its
  pages (zero-filled) until a free-page list exists. Simple and obviously correct.
- **Catalog lock is catalog-wide**, not per-entry, because all catalog entries share pages.
- **Statement-level rollback** (MySQL/Oracle semantics), not PostgreSQL's abort-the-transaction.
  Deadlock victims, though, are rolled back entirely, or the deadlock would persist.
- **Redo-only, full-page-image WAL** kept (no ARIES undo needed under no-steal); full images
  also repair torn data pages. Cost: ~4 KB of log per small commit (see "Not done").
- **Group commit applies pages after the fsync**; strict 2PL makes this order-safe, and a
  per-page sequence check fails stop if that invariant is ever broken.
- **Sharp (blocking) checkpoints**; the stall is bounded by the buffer pool size.
- **B+Tree as the default numeric index**, hash index kept and selectable (`USING HASH`).
- **FIFO lock queues** trade median latency for a bounded tail, at equal throughput.
- **No native (C++/JNI/FFM) code**: profiles show the costs are fsyncs, round trips, allocation
  and algorithms; the CPU-bound pieces are sub-microsecond (parse 0.8 µs, row decode 70 ns).
- **No binary wire protocol**: measured overhead doesn't justify it (T11).

## Not done (known limitations, in rough priority order)

1. **Write amplification**: the WAL logs full 4 KiB page images (~70× for a 60-byte row).
   Logging page deltas after the first post-checkpoint image of each page (PostgreSQL's
   `full_page_writes` scheme) would cut this while keeping torn-page protection.
2. **Hot-table write scaling**: one table's writers serialize on its X lock through the commit
   fsync (strict 2PL). Row-level locking or early lock release would let them share fsyncs.
3. **Space reclamation**: no free-page list (rolled-back allocations and empty B+Tree leaves are
   never reused); deleted space is reclaimed only by in-page compaction.
4. **Hash index has a fixed bucket count**; linear or extendible hashing would make it O(1).
5. **Buffer pool copies every page** on every access (a 4 KiB copy is most of a cache hit's cost)
   and runs under one lock; pin/unpin with latches would remove both.
6. **Predicates evaluated after decoding**; unindexed scans still decode every row.
7. **Round trips**: no statement pipelining in the protocol; thread-per-connection server.
8. **SQL surface**: no joins, aggregates, `DROP`, `NOT`, `IS NULL`; B+Tree keys are numeric only.
9. **Authentication, roles, permissions**: not implemented.
10. **Power-loss testing**: crash tests cover failed writes, torn page writes and `kill -9`, not
    loss of data the OS had acknowledged (that needs fault injection below the file system).
