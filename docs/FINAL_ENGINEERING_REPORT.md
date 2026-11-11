# StrataDB final engineering report

This report records what was verified on 2026-11-11, after the 2026-11 engineering upgrade on branch `upgrade/roadmap`, last commit `9c16c1d`. It was written by checking the source, tests, git history, CI configuration and raw benchmark output, not by restating the README. Where the README or other docs and the evidence disagree, this report says so.

[`ENGINE.md`](ENGINE.md) explains how the engine works, [`AUDIT.md`](AUDIT.md) records the audit and its decisions, and [`BENCHMARKS.md`](BENCHMARKS.md) gives the full methodology. All three are unchanged by this report.

## How to read this report

Every claim carries one of three labels.

| Label | Meaning | Where the evidence is |
|---|---|---|
| **[MEASURED]** | A number produced by the benchmark harness or JMH | `bench-results/<label>/results.csv`, `results.md`, `jmh*.txt` |
| **[IMPLEMENTED]** | Behaviour that exists in the code and is exercised by a named test | `src/main/java/...`, `src/test/java/...` |
| **[RECOMMENDATION]** | An engineering opinion or future work, not a fact about the current system | none: it is a judgement |

### Evidence sources checked

| Source | What it gave |
|---|---|
| `git log` | 16 commits: the initial import (`7f75ea6`, 2026-10-04), then T0–T11, docs and validation (2026-11-11) |
| `git diff --shortstat 7f75ea6 HEAD` | 91 files changed, +7,970 / −590 lines |
| Source tree | 46 main classes (6,860 lines; 4,593 at the initial commit), 39 test-side files (5,777 lines), 919 lines of benchmark code |
| Test methods | **296** at `HEAD` and **236** at `7f75ea6`, counted from method names; `mvn -B verify` reports `Tests run: 296, Failures: 0, Errors: 0, Skipped: 0` |
| CI (`.github/workflows/ci.yml`) | `mvn -B verify` on `ubuntu-latest` and `windows-latest`, Temurin JDK 21, run on every push and pull request |
| Benchmark output | `bench-results/{baseline, t6…t11, final-ab-*}`: CSVs with every run, not only the medians |

---

## 1. Feature inventory

Status key: **Complete** means implemented, wired into the live path, and tested. **Partial** means it works with stated limits. **Unsupported** means it does not exist.

| Subsystem | What is actually implemented | Main files / classes | Key algorithms and data structures | Tests that verify it | Status |
|---|---|---|---|---|---|
| Page I/O | Fixed 4,096-byte pages; page *n* at byte *n* × 4096. Positional `FileChannel` I/O; no fsync per write, fsync only through `sync()`. A partial trailing page left by a crash during file growth is dropped. | `storage/page/Page`, `DiskManager`, `PageStore`, `PageStores` | `PageStore` interface implemented by `DiskManager`, `BufferPool` and `Transaction` | `PageTest` (8), `DiskManagerTest` (5) | Complete |
| Slotted page | 12-byte header (slot count, free-space offset, next page id). 8-byte slots grow forward and records grow backward. Deletes leave tombstones. In-place `updateRecord`, `compact()`, and `isFormatted` (used to validate tail hints). | `storage/SlottedPage` | Slot directory; compaction moves record bytes but never changes slot indexes, so RecordIds stay stable | `SlottedPageTest` (11), `InPlaceUpdateTest` (4) | Complete; free slots are never reused |
| Heap table | A linked chain of slotted pages. Inserts start at a per-database tail hint, not the first page. In-place update when the row fits. Push-style `scan(visitor)` and `visit(rids, visitor)`. | `storage/HeapTable`, `RecordId` | Tail-page hint validated against unformatted (all-zero) pages | `HeapTableTest` (8), `HeapTailHintTest` (3) | Complete; no free-space map or page reuse |
| Buffer pool | LRU cache of committed pages with lazy dirty write-back on eviction or checkpoint. Default 2,048 pages (8 MiB) via `Database.Options`. Copies pages in and out. Counts hits, misses, evictions and write-backs. | `buffer/BufferPool` | `LinkedHashMap` in access order with `removeEldestEntry`; one monitor | `BufferPoolTest` (11), `SessionBufferPoolTest` (6) | Complete; no pin/unpin, single lock |
| Catalog | A heap file rooted at page 0. Tagged records: 0 = table, 1 = hash index, 2 = B+Tree index. Loaded lazily, after the catalog lock is held. | `catalog/Catalog`, `TableInfo`, `IndexInfo`, `ColumnDefinition`, `DataType` | Bootstrap convention (page 0); tags keep the format extensible | `CatalogTest` (17), `TableInfoTest` (3) | Complete |
| Hash index | Static hash index with 16 buckets by default; each bucket is a heap file. Equality lookups only. | `index/hash/HashIndex`, `EntryCodec` | `floorMod(hash, buckets)`; overflow by page chaining | `HashIndexTest` (12) | Partial: buckets never grow, so lookups are O(N/16) |
| B+Tree index | Keys are `long` (INTEGER, BIGINT, BOOLEAN). Entries are ordered by (key, RecordId), so every entry is unique. A meta page holds the root. Leaves hold 255 entries and are linked left to right; internal nodes hold 204. Splits propagate up; deletes never merge. `verify()` checks all invariants. | `index/btree/BPlusTree`, `index/SecondaryIndex` | Composite-key B+Tree, leaf chain for ranges | `BPlusTreeTest` (6, including a randomized model test), `RangePlanTest` (5) | Complete for numeric keys; no VARCHAR keys, no merging |
| SQL lexer | Hand-written scanner; keywords are case-insensitive; doubled `''` escapes a quote; `--` comments | `lexer/Lexer`, `Token`, `TokenType` | Single-pass scanner with a keyword map | `LexerTest` (18), `TokenTest` (4) | Complete for the supported subset |
| SQL parser and AST | Recursive descent into a sealed-record AST. Supports CREATE TABLE, CREATE INDEX `[USING HASH\|BTREE]`, INSERT, SELECT (WHERE, ORDER BY, LIMIT), UPDATE, DELETE, BEGIN/COMMIT/ROLLBACK. | `parser/Parser`, `parser/ast/*` | Precedence comes from the call chain (or → and → comparison → primary) | `ParserTest` (28) | Partial: no JOIN, aggregates, NOT, IS NULL, DROP or ALTER |
| Query planner | Rule-based. Splits WHERE into top-level AND terms; equality on any index gives `IndexScan`, a range on a B+Tree column gives `IndexRangeScan` (tightest bounds win), otherwise `FullScan`. A literal of the wrong type never uses an index. | `optimizer/QueryPlanner`, `ScanPlan` | Rule-based access-path selection; the full WHERE is re-checked on every candidate | `QueryPlannerTest` (8), `RangePlanTest` (5) | Complete for its rules; no statistics or costs |
| Executor | Push pipeline: access path → decode the row in place on the page → filter → collect. LIMIT without ORDER BY stops the scan early. UPDATE and DELETE collect matches first, which avoids the Halloween problem. Index maintenance skips indexes whose key did not change. | `executor/Executor`, `RowSerializer`, `Row`, `ExecutionResult` | SQL three-valued logic; in-place decoding with an ASCII fast path | `ExecutorTest` (37), `ExecutionPipelineTest` (4), `RowSerializerTest` (4) | Complete for the supported subset |
| Transactions | A private page overlay per transaction. Page ids are allocated from the shared store. Statement savepoints (`beginStatement` / `commitStatement` / `rollbackStatement`). Commit goes through `WriteAheadLog.commit`. | `transaction/Transaction`, `Session` | Before-image on the first touch of each page per statement | `TransactionTest` (17), `SessionTest` (10), `TransactionLifecycleTest` (6) | Complete |
| Isolation | Strict two-phase locking on a catalog lock plus table S/X locks | `transaction/Session` | Catalog lock: X for DDL, S otherwise | `SessionConcurrencyTest` (4), `ConcurrencyRegressionTest` (5) | Complete at table granularity; no row locks, no MVCC |
| Lock manager | FIFO queue per resource with one `Condition` per request. Deadlocks are detected on the waits-for graph when a request enqueues; the requester that closes the cycle is aborted. Timeout (default 5 s) kept as a backstop. | `lock/LockManager`, `DeadlockException`, `LockTimeoutException` | `ReentrantLock` latch, FIFO granting, cycle detection | `LockManagerTest` (10), `LockManagerV2Test` (5) | Complete |
| WAL and group commit | No-steal, no-force, redo-only log of full page images. Commit = append, wait until durable (the first waiter becomes the fsync leader for everything appended), then apply to the buffer pool. Record format v2: `magic \| length \| payload \| CRC32C`, with payload `txnSeq \| durableHorizon \| pageCount \| (pageId, image)…`. | `recovery/WriteAheadLog` | Leader-based group commit; per-page sequence check | `WriteAheadLogTest` (8), `GroupCommitCheckpointTest` (6), `RecoveryHardeningTest` (5) | Complete |
| Checkpoints | Sharp checkpoint: block commits, flush dirty pages, fsync the data file, truncate the log. Runs automatically past 16 MiB of log and on clean close. | `recovery/WriteAheadLog.checkpoint`, `engine/Database` | Blocking (non-fuzzy) checkpoint | `GroupCommitCheckpointTest` | Complete |
| Crash recovery | On open: replay every valid record in log order, fsync, delete the log. A damaged record counts as a torn tail unless a later valid record's `durableHorizon` proves it had been acknowledged; then recovery refuses to proceed. If applying a commit fails after it is durable, the log refuses further commits until reopen (fail-stop). | `recovery/WriteAheadLog.recover`, `engine/Database` | Torn-tail vs corruption detection via the durable horizon | `RecoveryHardeningTest` (5), `CrashInjectionTest` (40 seeds), `ProcessKillTest` (3 kill rounds) | Complete against process crashes and failed writes; power loss not tested |
| Snapshots | No snapshot isolation, no MVCC snapshots, and no backup or snapshot command | — | — | — | **Unsupported** |
| TCP server and client | Thread per connection with one `Session` each, always closed (rolled back, locks released) when the connection ends. Text protocol: one statement per line; replies `OK ROWS n`, header, *n* rows, `END`. Values escaped like PostgreSQL's COPY text format; a VARCHAR equal to `NULL` is sent as `\NULL`. | `network/DatabaseServer`, `DatabaseClient`, `Protocol`, `Main` (shell / server / client modes) | Framing by row count, escaping | `DatabaseServerTest` (10), `ServerRegressionTest` (4) | Complete; no auth, TLS or connection limits |
| Engine facade and metrics | `Database` opens, bootstraps or recovers one file and owns the shared components; `Options` (pool pages, lock timeout, checkpoint threshold); `metrics()` returns 17 counters | `engine/Database`, `metrics/Metrics` | — | `MetricsTest` (2) | Complete |
| Concurrency primitives | `ReentrantLock` and `Condition` (lock manager, WAL), `synchronized` (buffer pool, disk manager), atomics | across the modules above | — | concurrency tests above | Complete |
| Authentication and authorization | None | — | — | — | **Unsupported** |

---

## 2. Engineering challenges and fixes

### 2.1 The six reproduced correctness bugs

Each bug was demonstrated by a program before it was fixed, and each now has a regression test [IMPLEMENTED]. The numbering follows [`AUDIT.md`](AUDIT.md).

#### Bug 1: concurrent transactions were given the same new page id (critical)

- **Symptom.** Concurrent inserts into different tables put rows into the wrong table. Concurrent autocommit inserts into one table could hang forever while holding the table's X lock.
- **Root cause.** `Transaction.allocatePage()` handed out ids from `overlayNumPages`, a private copy of the page count taken when the transaction began. Two transactions started from the same count both received "page N" and both committed different contents to it.
- **Why it happened.** Table locks protect table names, but the page-id space is shared by every table. That shared state had no protection. For one table, the duplicate allocation linked a page into the chain twice, forming a cycle, and `HeapTable.insert` walked that cycle forever.
- **Fix** (T1, `0056f85`). Page ids are reserved in the shared store at allocation time, through `PageStore.allocatePage(s)`, so they are globally unique. A rolled-back transaction leaks its pages as zero pages; later, tail hints reject those pages using `SlottedPage.isFormatted`.
- **Regression tests.** `ConcurrencyRegressionTest.testConcurrentTransactionsGrowingDifferentTablesDoNotShareNewPages`, `…testConcurrentAutocommitInsertsIntoOneTableAreAllKept`.
- **Impact.** Correctness: this is cross-table data corruption plus a livelock. It also made every concurrent benchmark meaningless, which is why the baseline was taken only after T1–T4.

#### Bug 2: concurrent CREATE TABLE lost a table (critical)

- **Symptom.** Two sessions created differently named tables at the same time. Both received "OK", but only one table existed afterwards.
- **Root cause.** Catalog pages were outside the lock protocol. Each transaction committed a full-page copy of the catalog page from its private overlay, so the last writer won.
- **Fix** (T2, `8eac1ef`). A database-wide catalog lock: X for DDL, S for every other statement, always taken before table locks.
- **Regression tests.** `ConcurrencyRegressionTest.testConcurrentCreateTablesDoNotLoseEitherTable`, `…testDdlWaitsForTransactionsThatHaveReadTheCatalog`.
- **Impact.** Correctness: DDL can no longer lose an update. The cost is that DDL now excludes every other statement while it runs.

#### Bug 3: explicit transactions used a stale catalog (critical)

- **Symptom.** Session A ran `BEGIN`; then session B created an index. A's later inserts did not maintain that index, so the index silently missed rows.
- **Root cause.** The catalog was loaded at `BEGIN`, before any lock was held.
- **Fix** (T2). The catalog is loaded lazily, only after the transaction's first catalog lock. Because the lock is held to the end of the transaction, the cached copy cannot go stale.
- **Regression test.** `ConcurrencyRegressionTest.testIndexCreatedAfterAnotherSessionsBeginIsStillMaintained`.
- **Impact.** Correctness: indexes stay consistent with their tables.

#### Bug 4: a failed statement inside BEGIN left partial changes (high)

- **Symptom.** A failing `UPDATE` inside an explicit transaction had already deleted the row, and `COMMIT` kept the deletion.
- **Root cause.** No statement-level atomicity: a statement's partial writes stayed in the overlay after it threw an exception.
- **Fix** (T3, `ba2a296`). Statement savepoints. The first touch of each page in a statement saves that page's before-image; a failure restores them, including pages that were mutated in place, and the transaction continues. A deadlock victim's whole transaction is rolled back instead, because otherwise the deadlock would persist.
- **Regression tests.** `TransactionLifecycleTest.testFailedStatementInsideTransactionLeavesNoTrace`, `…testFailedDdlInsideTransactionIsUndoneIncludingTheCatalogCache`, `…testStatementSavepointRestoresOverlayPagesMutatedInPlace`.
- **Impact.** Correctness: statements are atomic (MySQL/Oracle semantics).

#### Bug 5: a client that disconnected mid-transaction held its locks forever (high)

- **Symptom.** Client A ran `BEGIN; INSERT …;` and dropped the socket. Every later statement on that table timed out.
- **Root cause.** The connection handler never rolled back or released locks when it exited.
- **Fix** (T3). `Session.close()` rolls back and releases all locks, and the server always closes the session, however the connection ends. Failed autocommit statements also release their locks.
- **Regression tests.** `ServerRegressionTest.testDisconnectMidTransactionRollsBackAndReleasesLocks`, `TransactionLifecycleTest.testFailedAutocommitStatementReleasesItsLocks`.
- **Impact.** Availability: one bad client can no longer block a table permanently.

#### Bug 6: a VARCHAR value `'END'` desynchronised the wire protocol (medium)

- **Symptom.** A row containing the string `END` ended the response early, so the client read every later line as the answer to the wrong statement.
- **Root cause.** Responses were framed by an `END` sentinel line that row data could also contain.
- **Fix.** In T3, responses are framed by row count (`OK ROWS n`) and values are escaped (tab, newline, backslash). In T11 (`413976a`), the string `'NULL'` is sent as `\NULL` so it is distinct from SQL NULL.
- **Regression tests.** `ServerRegressionTest.testValueEqualToEndSentinelDoesNotBreakFraming`, `…testTabsAndBackslashesInValuesAreEscaped`, `…testStringNullIsDistinguishableFromSqlNull`.
- **Impact.** Correctness: any value can be sent over the wire without breaking framing.

The audit also found and fixed six lower-severity issues: findings 7–12 in `AUDIT.md`. These covered WAL corruption handling, log-order versus apply-order divergence, a half-applied commit after an apply failure, a stale `.wal` file reused for a new database, an unfair lock manager, and an index scan disagreeing with a full scan.

### 2.2 The three hardest problems, in interview depth

#### (1) No-force group commit that stays correct when pages are applied out of log order

**Problem.** The baseline paid about 2 fsyncs per autocommit insert and about 5 per indexed update [MEASURED: 2.03 and 4.99 fsyncs per operation in `baseline/results.csv`]. One fsync went to the WAL and one went to each dirty data page flushed at commit (the FORCE policy). Every commit also fsynced inside one global lock, so 8 writers on 8 separate tables barely beat one writer: 709 vs 550 ops/s.

**Design.**
1. **No-force.** A commit is acknowledged once its log record is durable. Data pages are written later, by eviction or a checkpoint. The WAL rule holds by construction: a page enters the buffer pool only after its commit record is durable.
2. **Group commit.** `commit` appends the record without fsync, then waits until the log is durable past its end offset. The first waiter becomes the leader and fsyncs everything appended so far. Committers that arrived during that fsync are covered by it, or by the next leader.
3. **Apply after the fsync.** Applying page images happens outside the append critical section, so two commits may apply in a different order from their log order. That is safe only if they never write the same page. Strict 2PL guarantees that, because a page belongs to one table and the table's X lock is held until the commit is applied. A per-page sequence check enforces the invariant and fails stop if it is ever broken.
4. **Recovery under concurrency.** With several records in flight, a damaged record in the middle of the log is ambiguous: it could be a torn append that was never acknowledged, or corruption of an acknowledged commit. Each v2 record carries a `durableHorizon`, the log offset known to be durable when the record was appended. If any later valid record's horizon lies past the damaged offset, the damaged record had been acknowledged, so recovery refuses to proceed rather than silently drop committed work. Otherwise the damage is a torn tail and is ignored.

**Result.** 1.00 fsync per commit, and 0.26 with 8 concurrent writers on separate tables. Throughput: autocommit insert ~2–3×, indexed update ~4–6×, 8 independent writers ~4–5× [MEASURED: final interleaved A/B].

**Tests.** `GroupCommitCheckpointTest.testConcurrentCommitsShareFsyncs`, `…testTornRecordBeforeALaterDurableRecordIsCorruption`, `…testTornRecordFollowedOnlyByUnacknowledgedRecordsIsATornTail`, `RecoveryHardeningTest`, `CrashInjectionTest`, `ProcessKillTest`.

**Remaining limit.** Writers to one table still serialize: each holds its X lock through its own fsync, so they cannot share an fsync. That scenario stays at 1.00 fsync per commit and improved only ~1.5–2× in throughput.

#### (2) The shared page-id race (bug 1) and why table locks could not catch it

**Why it is hard.** Every component was thread-safe on its own: `DiskManager` and `BufferPool` were synchronized, and every table was locked. The bug lived in the composition. `Transaction` kept its own view of the page count, and allocation belonged to no lock. The failure was timing-dependent, and it showed up as rows appearing in the wrong table or as an infinite loop, never as an exception.

**Fix and reasoning.** Two designs were possible: reserve ids eagerly in the shared store, or resolve conflicts at commit time. Eager reservation was chosen because it is obviously correct. The cost is that a rolled-back transaction leaks pages until a free-page list exists. That leak in turn invalidated the new heap tail hint, a cache of each table's last page that could point at a leaked, never-formatted page. So the hint is validated against all-zero pages (`HeapTailHintTest.testHintLeftByARolledBackTransactionIsIgnored`).

**Interview point.** Locks must cover all shared state a transaction writes, not only the objects users name. The catalog (bug 2) and the page allocator (bug 1) are the same lesson.

#### (3) Lock manager v2: fairness, deadlock detection, and rolling back the victim

**Problem.** The original manager used one monitor with `notifyAll` and let late arrivals barge ahead. An unlucky writer could lose race after race; the baseline same-table maximum was 1.45–1.83 s [MEASURED]. Deadlocks, including two S holders both upgrading to X, were broken only by the 5 s timeout.

**Design.**
- **FIFO queues.** One queue per resource, with one `Condition` per request, so a release wakes exactly the requests that can now be granted. Compatible waiters at the head are granted together.
- **Deadlock detection.** When a request enqueues, the manager searches the waits-for graph from the requester (`closesCycle`). If the requester would close a cycle, it receives a `DeadlockException` immediately.
- **Victim rollback.** Statement-level rollback (bug 4) is not enough for a victim: it still holds its earlier locks, so the cycle would remain. `Session` therefore rolls back the victim's whole transaction.

**Result** [MEASURED, interleaved A/B of T9 vs T10, 8 writers on one table]. Throughput unchanged (1,074–1,076 vs 994–1,076 ops/s). p99 fell from 108–130 ms to 11–15 ms, and the maximum from 661–744 ms to 12–17 ms. p50 rose from 1.4 ms to 7.5 ms. That is a deliberate trade: FIFO bounds the tail at the cost of the median.

**Tests.** `LockManagerV2Test.testDeadlockIsDetectedImmediatelyNotByTimeout`, `…testUpgradeDeadlockIsDetected`, `…testWaitingWriterIsNotStarvedByLaterReaders`, `…testDeadlockVictimTransactionIsRolledBackAndTheOtherCommits`.

---

## 3. Performance results

### 3.1 Environment and method (applies to every result below)

| Item | Value | Source |
|---|---|---|
| Machine | 16 logical CPUs, Windows 11 10.0 (amd64) | recorded in every `results.md` header |
| Storage | consumer NVMe SSD | stated in `BENCHMARKS.md`; **not** recorded by the harness |
| JVM | Java HotSpot 64-Bit Server VM 23.0.1+11-39, G1, `-Xms1g -Xmx1g` | `results.md` header |
| Build target vs bench JVM | code targets Java 21 (CI runs on JDK 21); benchmarks ran on JDK 23 | `pom.xml`, CI, `results.md` |
| Workload harness | Fresh database per run; 1 warm-up run at 1/10 size; **3 measured runs**; reports the run with the median throughput plus the min–max spread; latencies are exact nearest-rank percentiles over every operation; buffer pool 1,024 pages (hit ratio 1.000) | `WorkloadBenchmark`, `BENCHMARKS.md` |
| Data | Rows `(id INTEGER, val BIGINT, pad VARCHAR)`, ~60 bytes encoded. Medium scale: 2,000 autocommit inserts; 20,000 bulk rows in transactions of 500; 10,000 lookups; 100 range queries; 10 full scans of 20,000 rows; 1,000 updates; 300 inserts per thread; 2,000 mixed operations per thread | `results.md` header |
| JMH | `AverageTime`; 3 × 1 s warm-up, 5 × 1 s measurement, 1 fork; errors are JMH's 99.9% confidence intervals | annotations in `src/bench/java/.../micro` |
| Noise floor | Two identical workloads (one thread, one table) measured 893 vs 1,186 ops/s in one run. Treat ±25% in fsync-bound scenarios as noise; CPU-bound scenarios are about ±10% | `BENCHMARKS.md` |

**Final validation method.** The baseline build (`93eb21d`: the engine as of `7c89746` plus the harness) and the final build were run **interleaved**: baseline, final, baseline, final. Each cell below is the median-throughput run of 3; "r1 / r2" are the two rounds.

### 3.2 End-to-end results (final interleaved A/B) [MEASURED]

Raw data: `bench-results/final-ab-{baseline,final}-r{1,2}/results.csv`.

| Scenario (unit) | Baseline r1 / r2 | Final r1 / r2 | Change | Other evidence from the same rows | Variability and caveats |
|---|---:|---:|---:|---|---|
| `insert_autocommit` (ops/s) | 753 / 487 | 1,462 / 1,413 | ~2–3× | fsyncs/op 2.03 → 1.00; p99 2.68–4.08 ms → 1.08–1.40 ms | Baseline rounds differ by 35%: the fsync-bound noise floor |
| `insert_batched` (rows/s, transactions of 500) | 10,456 / 7,419 | 158,649 / 122,084 | ~15× | p99 per transaction 103–109 ms → 4.5–7.5 ms; allocation ~13.5 GB → ~150 MB per run | Large effect, well above noise |
| `point_lookup_index` (ops/s, 20k rows) | 9,243 / 9,272 | 62,561 / 85,372 | ~7–9× | Baseline uses the hash index, final the B+Tree; p99 259–274 µs → 41–57 µs | Final r2 runs spread from 52.9k to 127.7k ops/s |
| `range_query` (queries/s, 100 rows each) | 148 / 169 | 12,868 / 14,646 | ~87× | Baseline is a **full scan** (no ordered index existed); final is a B+Tree range scan; p99 10.1–11.8 ms → 0.30–0.34 ms | Compares two access paths, not one tuned path |
| `full_scan` (rows/s) | 3.7 M / 3.0 M | 5.4 M / 8.4 M | ~1.5–2.8× | Allocation 155 MB → 67 MB per run | **Noisy**: final runs ranged 3.2 M–14.6 M rows/s; treat as directional |
| `update_indexed` (ops/s) | 225 / 286 | 1,335 / 1,040 | ~4–6× | fsyncs/op 4.99 → 1.00; WAL bytes/op 16,330 → 4,136 | — |
| `concurrent_insert_own_table_t8` (ops/s) | 691 / 797 | 3,228 / 3,156 | ~4–5× | fsyncs/op 2.03 → 0.26 (about 4 commits per fsync) | — |
| `concurrent_insert_same_table_t8` (ops/s) | 523 / 700 | 1,035 / 1,044 | ~1.5–2× | p99 234.6 / 178.6 ms → 11.6 / 11.8 ms; max 1.45 / 1.83 s → 12.8 / 12.9 ms; p50 3.4 / 2.7 ms → 7.6 / 7.6 ms (**rose**) | Throughput gain is modest by design (§2.2 (1)) |
| `mixed_80read_20write_t8` (ops/s) | 1,640 / 1,942 | 3,998 / 4,696 | ~2.4× | p99 85.6 / 71.0 ms → 5.9 / 5.4 ms; max 301 / 279 ms → 15.7 / 11.6 ms | — |

### 3.3 Step-by-step results worth citing [MEASURED]

These come from separate runs, not interleaved, so treat them as within-step evidence.

| Step | Result | Source |
|---|---|---|
| T6 scaling (no-force, group commit) | `concurrent_insert_own_table`: 1 thread 1,319 → 8 threads 4,215 ops/s (3.2×). Baseline build: 550 → 709 (1.3×) | `t6-noforce-groupcommit`, `baseline` CSVs |
| T7 bulk insert (tail hint) | 12,189 → 178,584 rows/s; allocation 660 → 7.2 KB per row | `t7-storage-paths` |
| T7 in-place update | WAL bytes per indexed update 16,342 → 4,136 | `t7-storage-paths` |
| T9 full scan (streaming pipeline) | 3.36 M → 12.6 M rows/s; unindexed lookup allocation 15.3 → 6.0 MB per query | `t9-pipeline` |
| T10 lock manager (interleaved T9/T10) | p99 108–130 → 11–15 ms; max 661–744 → 12–17 ms; p50 1.4 → 7.5 ms; throughput unchanged | `t10-lockmanager`, `BENCHMARKS.md` |
| T11 protocol | Indexed lookup p50: 12 µs embedded vs 29 µs over localhost TCP (+17 µs). Text encoding of a one-row result: 0.36 µs (JMH) | `t11-protocol` |

### 3.4 JMH microbenchmarks [MEASURED, CPU only, all pages cached]

| Benchmark | Result (± 99.9% CI) | Source |
|---|---|---|
| Hash index lookup, N = 1k / 10k / 100k | 4.1 ± 1.9 / 34.7 ± 8.7 / **387.9 ± 168.3** µs | `t8-btree/jmh-access-path.txt` |
| B+Tree lookup, N = 1k / 10k / 100k | 2.04 ± 0.30 / 2.11 ± 0.30 / **2.77 ± 0.09** µs | same |
| B+Tree 100-key range, N = 1k / 10k / 100k | 3.5 ± 0.8 / 4.6 ± 1.9 / 4.4 ± 0.6 µs | same |
| Hash index lookup, N = 100k, baseline run | 379.5 ± 82.5 µs | `baseline/jmh.txt` |
| Buffer pool hit vs 4 KiB page copy alone | 369 ns vs 427 ns (the copy *is* the cost) | `baseline/jmh.txt` |
| `Page.getInt` × 1,024 | 113 ns, **0 bytes** allocated (escape analysis) | `baseline/jmh.txt` |
| Parse a SELECT / INSERT | 0.71 / 0.86 µs | `baseline/jmh.txt` |
| Row decode: stream decoder vs in-place decoder | 253 ns vs 70 ns | `t11-protocol/jmh-codec.txt` |

### 3.5 Verification of the README headline claims

| README claim | Verdict | Evidence and caveats |
|---|---|---|
| Fsyncs per commit: 2–5 before, 1 after (shared across concurrent committers) | **Substantiated** | Baseline: (disk + WAL fsyncs) / commits = 2.03 for autocommit insert, 4.99 for indexed update. Final: 1.00 (data-file fsyncs are 0 during the run). 8 writers on separate tables: 0.26 per commit. |
| Concurrent writers, 8 threads, separate tables: "flat (no scaling)" before, "scales with threads" after | **Mostly substantiated; wording overstated** | Baseline 1 → 8 threads is 550 → 709 ops/s, **1.3×, not flat**; a precise phrasing is "barely scales (1.3×)". After: 1,319 → 4,215 (3.2×) in the T6 run. The final A/B measured t8 only (3,156–3,228 ops/s, ~4–5× the baseline t8). |
| Indexed lookup at 100k rows (JMH): 388 µs (static hash) → 2.8 µs (B+Tree) | **Substantiated, with caveats** | 387.9 ± 168.3 µs vs 2.77 ± 0.09 µs, both measured in the T8 run. The hash interval is wide (±43%), but the ~140× gap far exceeds it. This compares two index structures in the same build, CPU only with pages cached. It shows linear vs logarithmic growth with a fixed bucket count, not hashing in general: a resizing hash index would be O(1). |
| 100-key range query: full table scan → B+Tree range scan | **Substantiated** | Implementation: `ScanPlan.IndexRangeScan`, checked against full scans in `RangePlanTest.testRangeQueriesMatchFullScans`. End to end: 148–169 → 12,868–14,646 queries/s (~87×). |
| Lock contention p99 (8 writers, one table): ~180–235 ms → ~12 ms | **Substantiated** | Baseline median-throughput runs: 234.6 ms and 178.6 ms. Final: 11.62 ms and 11.75 ms. Across all 6 final runs: 10.9–13.3 ms. |
| Maximum latency, same scenario: ~1.5–1.8 s → ~13 ms | **Substantiated** | Baseline median runs: 1.451 s and 1.826 s (all six runs: 0.66–2.01 s). Final median runs: 12.8 ms and 12.9 ms (all six runs: 12.5–19.1 ms). Note that median latency **rose** (~3 ms → ~7.6 ms); quote both numbers. |
| Tests: 236 (none run by `mvn test`) → 296, run by Maven and CI, including crash and kill tests | **Substantiated** | 236 test methods at `7f75ea6` and 296 at `HEAD`; `mvn -B verify` reports 296 run, 0 failed. "None run by `mvn test`" is from the audit record and the T0 commit (`8a9dd56`); this report did not re-run the old build. |

**Claim not to make.** "Durable against power loss." Crash testing covered failed writes, torn page writes and `kill -9`, not loss of data the OS had acknowledged.

---

## 4. Correctness and reliability

| Property | What holds | Basis |
|---|---|---|
| Transaction atomicity | All or nothing, including across process crashes. Uncommitted pages live only in the overlay, and a commit is one WAL record. | [IMPLEMENTED] no-steal overlay plus a single redo record; `CrashInjectionTest`, `ProcessKillTest` (batch atomicity) |
| Statement atomicity in explicit transactions | A failing statement is undone exactly and the transaction continues; a deadlock victim is rolled back entirely | [IMPLEMENTED] `TransactionLifecycleTest` |
| Isolation | Serializable **at table granularity**: strict 2PL on the catalog lock plus table S/X locks, held to commit. No phantoms, because the unit of locking is the whole table. No isolation levels, no MVCC, no snapshot reads. | [IMPLEMENTED] `Session`, `LockManager`; `SessionConcurrencyTest`, `ConcurrencyRegressionTest` |
| Locking guarantees | FIFO granting (no barging or writer starvation); deadlocks detected when a request enqueues; 5 s timeout as a backstop; all locks released on commit, rollback, failed autocommit, and session close | [IMPLEMENTED] `LockManagerV2Test`, `ServerRegressionTest` |
| WAL ordering | A page reaches the buffer pool (and so the data file) only after its commit record is fsynced. Log order and apply order may differ only for commits on disjoint pages; this is enforced by a per-page sequence check that fails stop. | [IMPLEMENTED] `WriteAheadLog.commit` |
| Commit acknowledgment | `COMMIT` returns after the record is durable in the log; data pages are written lazily | [IMPLEMENTED] `ProcessKillTest`: every acknowledged commit survives `kill -9` |
| Crash recovery | Redo-only replay of full page images (idempotent; repairs torn data pages); torn tail vs corruption decided by `durableHorizon`; refuses to start on corruption of an acknowledged record; a partial trailing page is dropped | [IMPLEMENTED] `RecoveryHardeningTest`, `GroupCommitCheckpointTest`, `CrashInjectionTest` (40 seeds, crashes in the STATEMENT, COMMIT and CHECKPOINT phases, alternating HASH and BTREE indexes, results checked by full scan and by index) |
| Test-strength check | A mutant that skips WAL replay was caught by the crash tests | recorded during T4 (session record); not re-run for this report |
| Snapshot consistency | **Not offered.** No MVCC or snapshot reads. Readers see committed data because of S locks, not snapshots. Copying the file while the server runs is not a consistent backup. | — |
| Consistency (the C in ACID) | Type checking and INTEGER range checks only; no PK, UNIQUE, NOT NULL or FK constraints | [IMPLEMENTED] `Executor` |
| Durability under power loss | **Not established**; depends on the OS and device honouring fsync, and no fault injection below the file system was done | — |

**How to describe it.** "Transactional, with atomic and durable commits against process crashes, and serializable isolation at table granularity." Do **not** say "fully ACID" or "production-grade": consistency constraints do not exist and power-loss durability is untested.

### Known limitations

These are taken from `AUDIT.md`, "Not done", and confirmed in code.

1. The WAL logs full 4 KiB page images: ~4.2 KB of log per ~60-byte row, about 70× write amplification.
2. Writers to one hot table serialize through each commit's fsync.
3. No free-page list: leaked and empty pages are never reused.
4. The hash index has a fixed bucket count.
5. The buffer pool copies every page and runs under one lock; there is no pin/unpin.
6. Predicates are evaluated after rows are decoded.
7. No statement pipelining; the server uses a thread per connection.
8. Limited SQL: no joins, aggregates, DROP, NOT or IS NULL; B+Tree keys are numeric only.
9. No authentication or permissions.
10. Power loss is not tested.

---

## 5. Recommendations [RECOMMENDATION]

1. **Change the README's "flat (no scaling)" to "barely scales (1.3×, 1 → 8 threads)"**, so every headline row is exact.
2. **Delta WAL records after the first full image of each page per checkpoint** (PostgreSQL's `full_page_writes` scheme). This attacks the largest remaining cost, write amplification, while keeping torn-page repair.
3. **Row-level locks with intention locks, or early lock release after the log append.** Either would let one table's writers share fsyncs.
4. **Re-run the final A/B on Linux and record the storage device in the harness header.** That would make the numbers reproducible on other machines.
5. **Free-page list**, which would also allow B+Tree node merging.
