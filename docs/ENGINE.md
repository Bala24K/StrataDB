# StrataDB engine internals

How the engine works after the 2026-11 upgrade: storage format, indexes, query execution,
transactions and concurrency, the write-ahead log and recovery, and exactly what is (and isn't)
guaranteed. [`ARCHITECTURE.md`](ARCHITECTURE.md) gives the module overview,
[`AUDIT.md`](AUDIT.md) the history, and [`BENCHMARKS.md`](BENCHMARKS.md) the measurements.

## 1. Components

```
 client ──TCP──► DatabaseServer ──► Session (one per connection)
                                       │  parse → lock → plan → execute → commit
                                       ▼
                     Executor ─► QueryPlanner ─► Catalog ─► HeapTable / HashIndex / BPlusTree
                                       │                         (all just pages)
                                       ▼
                     Transaction (private page overlay + statement savepoint)
                                       │ commit: WriteAheadLog.commit (append → group fsync → apply)
                                       ▼
                     BufferPool (LRU cache of committed pages, lazy write-back)
                                       │ eviction / checkpoint
                                       ▼
                     DiskManager (4 KiB pages in one file)

 Database = one file + its shared BufferPool, LockManager and WriteAheadLog
 LockManager: catalog lock + per-table S/X locks, FIFO queues, deadlock detection
```

Every storage layer implements the same small `PageStore` interface (`readPage`, `writePage`,
`allocatePage(s)`, `sync`), so a `HeapTable` or `BPlusTree` neither knows nor cares whether it
runs on a transaction's overlay, the buffer pool, or the raw file.

## 2. Storage format

**File.** An array of 4 KiB pages; page *n* starts at byte *n* × 4096. Page 0 is the root of the
catalog. Pages are never freed (no free-page list yet).

**Slotted page** (`SlottedPage`): header (slot count, free-space offset, next page id), a slot
directory growing forward, record bytes growing backward from the end of the page. A record's
address is `RecordId(pageId, slot)`; slots are never reused, so ids stay stable. Deletes
tombstone the slot. `compact()` packs live records to reclaim holes, changing only slot
offsets, never slot indices. `updateRecord()` rewrites in place when the new version fits
(compacting if needed), keeping the RecordId.

**Heap file** (`HeapTable`): a singly linked chain of slotted pages, identified by its first
page id. Inserts start at the table's last page via a per-database tail hint; stale hints
(from rolled-back transactions) point at never-formatted, all-zero pages and are rejected.

**Rows** (`RowSerializer`): per column, a null flag byte, then INTEGER (4 bytes), BIGINT
(8 bytes), BOOLEAN (1 byte), or VARCHAR (2-byte length + modified UTF-8). Rows must fit in one
page (≤ 4,076 bytes). Scans decode rows in place from the page buffer.

**Catalog** (`Catalog`): a heap file rooted at page 0 holding tagged records: tag 0 = table
schema, 1 = hash index, 2 = B+Tree index. New record kinds get new tags so old files stay
readable.

## 3. Indexes

Both implement `SecondaryIndex` (`insert`, `delete`, `search`) over `(key, RecordId)` entries.
NULL keys are not indexed; duplicates are allowed.

**Hash index** (`HashIndex`): a fixed number of buckets (16 by default), bucket *i* is a heap
file starting at page `first + i`. O(1) only while N / buckets stays small; lookups degrade
linearly beyond that (no resizing yet).

**B+Tree** (`BPlusTree`): keys are `long` (INTEGER, BIGINT, BOOLEAN). Entries are ordered by
`(key, RecordId)`, which makes every entry unique and removes all duplicate-key special cases.
A meta page records the root. Leaves hold 255 entries and are linked left to right; internal
nodes hold 204 separators. Splits propagate upward; deletes don't merge (underfull nodes are
allowed; invariants still hold). `verify()` checks all structural invariants and is used by the
randomized tests.

`CREATE INDEX i ON t (c)` builds a B+Tree for numeric/boolean columns and a hash index
otherwise; `USING HASH` / `USING BTREE` overrides.

## 4. Query planning and execution

**Planner** (`QueryPlanner`): splits `WHERE` into top-level `AND` terms and picks, in order:
equality on any index → `IndexScan`; two-sided range on a B+Tree column → `IndexRangeScan`
(tightest bounds win); one-sided range; otherwise `FullScan`. A literal of the wrong type
never uses an index. The index only narrows candidates: the full `WHERE` is re-evaluated on
every candidate row, so any plan that returns a superset is correct.

**Executor**: a push pipeline (access path → decode in place → filter → collect). `SELECT`
stops at `LIMIT` when there's no `ORDER BY`; `ORDER BY` sorts the matches. `UPDATE` and
`DELETE` collect matching `(RecordId, row)` pairs first and then modify, which prevents the
Halloween problem and avoids rewriting pages that the scan is still reading. Index
maintenance on `UPDATE` touches an index only if its key changed or the row moved.

## 5. Transactions and concurrency

**Transaction** (`Transaction`): a private overlay of modified pages on top of the buffer pool.
Reads see the transaction's own writes, then the latest committed state. Rollback discards
the overlay; commit hands it to the WAL. New page ids are reserved in the shared store at
allocation time (globally unique); a rolled-back transaction's pages leak as zero pages.

**Statement savepoints**: inside `BEGIN … COMMIT`, a failing statement is undone exactly (the
first touch of each page saves its before-image), and the transaction continues. A
deadlock victim's whole transaction is rolled back instead.

**Locking** (`Session` + `LockManager`), strict two-phase locking:

| statement | catalog lock | table lock |
|---|---|---|
| `SELECT` | S | S |
| `INSERT` / `UPDATE` / `DELETE` | S | X |
| `CREATE TABLE` / `CREATE INDEX` | X | X |

The catalog lock is always taken first, and all locks are held to the end of the transaction.
A transaction reads the catalog only after holding the catalog lock, so its cached catalog
can't go stale. Lock queues are FIFO with one condition per request; deadlocks are detected on
the waits-for graph when a request enqueues, and the requester closing the cycle is aborted.
Timeouts (default 5 s) remain as a backstop.

**Isolation, honestly**: serializable at table granularity. Readers and writers of a table
exclude each other for the rest of the transaction, and DDL excludes everything; there are no
phantoms because the unit of locking is the whole table. The price is concurrency: one
writer per table at a time.

## 6. Write-ahead log and recovery

**Policy: no-steal, no-force, redo-only.** Uncommitted changes live only in the overlay, so
nothing uncommitted ever reaches the buffer pool or the file (no undo needed). A commit is
acknowledged when its log record is durable; data pages are written later by eviction or a
checkpoint. The WAL rule holds by construction: a page enters the buffer pool only after its
commit record is durable.

**Commit path** (`WriteAheadLog.commit`):
1. Append the record (not yet durable).
2. Wait until the log is durable past it. The first waiter becomes the fsync leader for
   everything appended so far (**group commit**).
3. Apply the page images to the buffer pool.

Applying after the fsync means two transactions could apply in a different order than they
were logged. That's harmless because, under strict 2PL, transactions that commit concurrently
never write the same page; a per-page sequence check enforces this invariant and fails stop if
it's ever violated.

**Record format v2**: `magic | length | payload | CRC32C`, where the payload is
`txnSeq | durableHorizon | pageCount | (pageId, 4 KiB image)…`. Full page images make replay
idempotent and repair torn data pages.

**Checkpoint** (automatic past 16 MiB of log, and on clean close): block commits, flush every
dirty page, fsync the data file, truncate the log. The data file is fsynced only here and at
the end of recovery.

**Recovery** (on open): replay every valid record into the file in log order, fsync, delete
the log. A damaged record is a **torn tail** (ignored) unless a later valid record's durable
horizon shows it had been acknowledged, in which case it's **corruption** and recovery refuses
to proceed rather than silently drop committed work. A partial trailing data page (crash
during file growth) is dropped; its contents are in the log.

**Fail-stop**: if appending to the log fails, the partial record is truncated. If applying
fails after the record is durable, the log refuses further commits until the database is
reopened, and recovery completes the commit.

**How it is tested**: `CrashInjectionTest` runs seeded random workloads (both index types) in
which a data-file write fails, optionally after tearing the page, during a statement, a
commit, or a checkpoint, then recovers and compares against a model of acknowledged commits
through both a full scan and the index. `ProcessKillTest` force-kills a child JVM mid-workload
and checks durability, batch atomicity and index consistency. Recovery tests cover torn tails,
mid-group tears, corruption and fail-stop. These were verified to catch a mutant that skips
WAL replay.

## 7. Buffer pool

LRU over committed pages (`LinkedHashMap` in access order), capacity configurable
(`Database.Options.bufferPoolPages`, default 2,048 pages = 8 MiB). Pages are copied in and out
so no caller can mutate a cached page; dirty pages are written back on eviction or at a
checkpoint without fsync. Counters: hits, misses, evictions, write-backs.

## 8. Network

Thread per connection; one `Session` per connection, closed (rolled back, locks released)
however the connection ends. Text protocol: one statement per line; responses framed by row
count (`OK ROWS n`, header, n rows, `END`); values escaped like PostgreSQL's COPY text format;
a VARCHAR equal to `NULL` is sent as `\NULL`.

## 9. Guarantees at a glance

| Property | Guaranteed? |
|---|---|
| Atomicity of a transaction (all or nothing, including across crashes) | Yes |
| Atomicity of a statement inside a transaction | Yes (statement savepoints) |
| Durability once COMMIT returns | Yes, against process crashes (tested by kill and injected write failures); against power loss only as far as the OS/device honour fsync (not tested) |
| Isolation | Serializable at table granularity (strict 2PL, table + catalog locks) |
| Consistency of indexes with tables | Yes, maintained in the same transaction; checked after crashes in tests |
| Torn data pages | Repaired from full page images in the log |
| Detection of log corruption | CRC32C per record; acknowledged-record corruption refuses recovery |
| Deadlock freedom | Deadlocks are detected and one transaction aborted |
| Authentication / access control | **No** |
