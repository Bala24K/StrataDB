# Phase 11 — Recovery

Goal for this phase: close the one gap that's been named honestly, in writing, twice —
in Phase 7 ("no crash-safety during commit itself") and again in Phase 9 ("still no crash-safety
during commit itself... now just sitting one layer further down"). A Write-Ahead Log is what
actually closes it.

## 1. The exact gap, restated precisely

By Phase 9, `Transaction.commit()` applied a transaction's buffered writes to a `BufferPool`,
which only holds them in memory until `Session` calls `flushAll()` right after. If the process
crashes in that narrow window — after `commit()` returns "success" but before `flushAll()`
finishes writing every dirty page to the real file — the caller was told the transaction
committed, but some (or all) of its data may not actually be on disk. That's a real durability
violation, not a hypothetical one.

## 2. The fix: log first, apply second — and why the order is everything

A **write-ahead log** is a separate, append-only file. Before `Transaction.commit()` applies
anything to the real store, it now durably logs — appends and fsyncs — exactly what it's about
to write, to `WriteAheadLog`. Only after that log record is safely on disk does it proceed to
apply the same writes to the buffer pool as before.

This ordering is the entire idea, and it's why the technique is named "write-ahead": the log
entry always exists *before* the corresponding data change is attempted. If a crash happens
after the log write but before (or during) the data write, nothing is lost — the log already
has a complete, durable record of what should have happened, and the next startup can finish
the job. If a crash happens *before* the log write, the transaction never appeared to commit in
the first place, which is exactly correct.

## 3. Redo-only recovery — and why that's sufficient here, not a shortcut

A textbook ARIES-style recovery scheme has two halves: **redo** (finish applying committed
changes that didn't fully reach disk) and **undo** (roll back changes from a transaction that
was still in progress, if some of its dirty pages had already reached disk early under memory
pressure — a "steal" policy). StrataDB only implements the first half, and that's not an
incomplete implementation — it's a direct, correct consequence of a decision already made in
Phase 9: **NO-STEAL**. An in-progress transaction's writes live entirely inside `Transaction`'s
own private overlay and are never visible to the buffer pool, let alone disk, until that
transaction's own `commit()`. Nothing uncommitted can ever reach the real file in the first
place — so there is never anything to undo. Every record the WAL ever writes represents an
already-committed transaction, by construction, which is also why the log format doesn't need a
separate "commit marker" distinguishing committed from in-progress work: a record simply
*existing* in the log means committed.

(A future move to a "steal" policy — needed for transactions too large to buffer entirely in
memory — would require adding real undo logging. Naming that dependency here, rather than
building it prematurely, is the same honest-scoping discipline this project has used throughout.)

## 4. The record format, and why it needs both a length prefix and a checksum

Each WAL record is: a 4-byte length, a payload (transaction sequence number, then each
`(pageId, page bytes)` pair), and an 8-byte CRC32 checksum of the payload. Two different things
can go wrong with the *last* record in the file if the process crashes mid-write, and the format
is designed to catch both:

- **A torn write.** The process could die after writing some, but not all, of a record's bytes
  — including possibly not finishing the length prefix itself. `recover()` checks, before
  trusting anything, that there are actually enough remaining bytes in the file for the
  claimed payload length plus its checksum; if not, it stops there rather than reading garbage.
- **Silent corruption.** Even a record whose bytes all "made it" could theoretically be
  inconsistent (rare on real hardware, but worth defending against cheaply). The checksum is
  what catches this: if the CRC32 of the payload doesn't match what was stored, recovery treats
  it exactly like a torn record — stop, and don't trust anything after it either.

Both cases are exercised directly: `testRecoverIgnoresATornTrailingRecord` appends a few stray
bytes after a valid record and confirms the valid one still replays; `testRecoverStopsAtACorruptChecksum`
flips a single bit inside a second record and confirms the first (untouched) record still
replays while the corrupted one is safely discarded.

## 5. The test that actually proves the phase

Everything above is validated most directly by one test:
`testRecoveryFinishesACommitThatNeverReachedDiskBeforeTheCrash`. It constructs the exact scenario
Phase 9's docs described in words: a `Transaction` commits (which logs to the WAL and applies to
a `BufferPool`), but the test deliberately never calls `bufferPool.flushAll()` — simulating a
crash in precisely that gap. It then confirms, directly against the raw file, that the write
genuinely isn't there yet (proving the crash scenario is real, not a no-op). Only then does it
open a *fresh* `DiskManager` on the same file (simulating a process restart) and call
`WriteAheadLog.recover()` — and confirms the previously-missing write is now present. This is
the "Huge milestone" the original project plan named, made concrete and checked by an actual
test rather than just asserted in documentation.

## 6. Wiring recovery into `Session`: it has to run before anything else touches the file

`Session.open()` now calls `WriteAheadLog.recover()` as its very first action — before
constructing a `BufferPool`, before touching the `Catalog`, before anything. This has to be the
order: recovery might need to write directly to page 0 (the catalog's own page) or any other
page in the file, and nothing else is allowed to have cached or assumed anything about that data
until recovery has had the chance to bring it up to date. `Session.prepareWal()` bundles exactly
this — run recovery, then open the now-clean log for new writes — and, like `BufferPool` and
`LockManager` before it, the resulting `WriteAheadLog` must be shared across every `Session` on
the same database file: two independent log file handles racing to append at the same time would
corrupt the log itself.

## 7. What Phase 11 deliberately does not do

- **No checkpointing.** The log is only ever cleared at `recover()` — i.e., the next time the
  database is opened — never during normal operation. A long-running session logs every single
  committed transaction, including every autocommit statement, for its entire lifetime, so the
  log grows without bound. A production system periodically checkpoints (flush every dirty page,
  then safely truncate the log up to that point) to keep this bounded. Real, valuable future
  work, explicitly not required for correctness.
- **No undo logging**, as covered in section 3 — a direct, correct consequence of staying
  NO-STEAL, not a gap.
- **No logical or physiological logging.** Every record is a full physical after-image of each
  changed page — simple to reason about and to replay idempotently, at the cost of a WAL record
  being as large as the pages it describes. Real systems often log smaller, logical descriptions
  of changes for efficiency; a reasonable optimization for later.
- **Still no crash-safety mid-*recovery* itself in the most exotic case** (a second crash while
  replaying the log) — recovery re-running from the start of an un-cleared log on a subsequent
  attempt is safe today (every operation `PageStores.applyAll` performs is idempotent — writing
  the same final bytes again is always safe), but this hasn't been stress-tested with deliberate
  repeated interruption.

## 8. What to look at in the code

- `src/main/java/com/strata/storage/page/PageStores.java` — the shared "apply a batch of
  writes, growing the store as needed" logic, factored out so `Transaction.commit()` and WAL
  recovery share exactly the same behavior.
- `src/main/java/com/strata/recovery/WriteAheadLog.java` — the log itself: framing, checksums,
  and crash-tolerant replay.
- `src/main/java/com/strata/transaction/Transaction.java` — `commit()` now logs before applying,
  when a WAL is supplied.
- `src/main/java/com/strata/transaction/Session.java` — `prepareWal()`/`open()` run recovery
  before anything else touches the database file.
- `src/test/java/com/strata/recovery/WriteAheadLogTest.java` — 8 tests, including the
  simulated-crash scenario described in section 5 and the torn/corrupt-record handling from
  section 4.
