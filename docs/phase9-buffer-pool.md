# Phase 9 — Buffer Pool

Goal for this phase: an in-memory page cache sitting in front of disk, so most reads and writes
stop paying a real disk I/O cost on every single call. This phase also, honestly, broke
something Phase 7 had already declared solved — and finding and fixing that is most of what's
worth understanding here.

## 1. The problem: every page access has paid full disk cost, every time

Every phase up to now, reading page 7 twice in a row meant two real reads through
`DiskManager` — even though nothing changed in between. Writing meant an immediate write *and*
an fsync, every time (Phase 0's durability decision). That's correct, but it's also the
slowest possible way to do it: a page that's read or written repeatedly (a hot catalog page, a
table's most recently used page) pays that full cost on every access instead of just the first.

## 2. `BufferPool` is the *third* thing to implement `PageStore`

`Transaction` (Phase 7) and `BufferPool` both implement the same `PageStore` interface, for two
completely different reasons: `Transaction` buffers writes so a whole batch can be committed or
discarded atomically; `BufferPool` caches pages so repeated access is fast. Neither one needed
to know the other exists — `Session` just composes them, constructing each `Transaction` on top
of the shared `BufferPool` instead of directly on the raw `DiskManager`. This is the same payoff
Phase 7 got from extracting `PageStore` in the first place, showing up again for a completely
unrelated reason.

## 3. Caching reads, deferring writes

- **`readPage`** returns a cached copy if one exists; a miss reads through to the real store
  once and remembers the result.
- **`writePage`** only updates the cache and marks that page **dirty** — it does not write
  through to the real store. This is the actual performance win: a page modified several times
  before ever being evicted costs one real disk write total, not one per modification.
- A dirty page only reaches disk when it's evicted, or when `flushAll()` is called explicitly.

## 4. LRU eviction, using a JDK collection instead of hand-rolling a linked list

The classic way to implement an LRU cache is a hand-written doubly-linked list plus a hash map
for O(1) lookup. `BufferPool` doesn't build one: `java.util.LinkedHashMap`, constructed with its
*access-order* flag set, already maintains exactly that ordering — every `get` or `put` moves an
entry to the "most recently used" end automatically. Overriding `removeEldestEntry` lets the
cache evict the least-recently-used page the instant it grows past capacity, flushing it first
if it's dirty. This is a plain JDK collection, not a borrowed caching library — entirely in
keeping with "built from first principles" — it's just a genuinely well-suited standard-library
tool for this exact job, the same way `DataOutputStream` was the right tool for Phase 2's
sequential binary encoding.

## 5. A question this phase reopens: is a "committed" transaction still durable?

Phase 7 pointed at Phase 0's fsync-on-every-write as the entire source of transaction
durability. Deferred writes put that at risk: if `Transaction.commit()` now only replays its
buffer through `BufferPool.writePage()` — which just marks pages dirty in memory — a "committed"
transaction's data might exist nowhere but RAM. A crash right after would lose data the caller
was already told was safely committed.

`BufferPool` doesn't solve this on its own; it only provides `flushAll()` as a tool. `Session`
is what actually closes the gap: it calls `bufferPool.flushAll()` immediately after every
`COMMIT` (and after every autocommit statement), *before* returning control to the caller. This
restores exactly the guarantee Phase 7 had — a committed transaction is durable the instant
`execute()` returns — verified directly in `SessionBufferPoolTest`, which reads straight from a
raw `DiskManager` (bypassing `Session`/`BufferPool` entirely) right after a commit and confirms
the data is already there.

This combination has a name in database terminology: **NO-STEAL / FORCE**. "No-steal" because an
in-progress transaction's uncommitted writes never reach `BufferPool` at all — they live in
`Transaction`'s own private overlay until its own commit. "Force" because every dirty page a
transaction produced is pushed to disk before its commit is acknowledged. It's the simplest
possible correct combination, and it's simple *specifically because* it doesn't need a
Write-Ahead Log. Phase 11 is where that changes — once "steal" (letting a large, still-
uncommitted transaction's pages reach disk early, under memory pressure) becomes worth doing,
safely undoing a stolen-but-later-aborted page is exactly the problem a WAL exists to solve.

## 6. A real bug the test suite caught: mutable pages aliased across a cache

This is worth walking through in full, because it's a genuinely interesting bug, not a shallow
one — three tests in the pre-existing `SessionTest` (from Phase 7) started failing the moment
`BufferPool` was wired in, and the reason took real tracing to find.

Every layer in this codebase, since Phase 0, has followed the same pattern: read a page, mutate
it in place (`SlottedPage`'s methods all mutate the `Page` object they're given), then explicitly
write it back. This was always safe because `DiskManager.readPage()` allocates a brand-new
`Page` object from freshly-read bytes on *every single call* — nobody could ever be mutating an
object anyone else still held a reference to.

`BufferPool` breaks that assumption silently: its cache *reuses the same `Page` object* across
multiple `readPage()` calls for the same page id. So the sequence that broke was:

1. Inside a transaction, `Catalog`/`HeapTable` code reads page 0 through `Transaction` →
   `BufferPool`, which hands back its own cached `Page` object directly (no copy).
2. That code mutates the page in place (inserting a new catalog row) — exactly the pattern
   every earlier phase always used safely.
3. The mutated object is written back into the *transaction's own* overlay — which is correct,
   and would have been fine on its own.
4. But because step 1 handed out the buffer pool's own live object rather than an independent
   copy, the mutation in step 2 also silently corrupted `BufferPool`'s cached page — even though
   nothing was ever explicitly written through to it, and even though the transaction was later
   **rolled back**.
5. `ROLLBACK` correctly discarded the transaction's own overlay entry — but the buffer pool's
   cache was already corrupted independently of that, so the "rolled back" change was still
   visible afterward.

The general lesson: **any layer that hands out a reference to a mutable object it's caching must
either make that object immutable, or hand out a defensive copy** — otherwise a caller mutating
what they were given (a completely normal, previously-safe thing to do) can corrupt the cache
through a reference the caller doesn't even realize is shared. The fix was `Page.copy()`, used
on both sides of `BufferPool`'s boundary: `readPage()` returns a copy of what's cached (so a
caller's later mutation can't reach the cache), and `writePage()` stores a copy of what it's
given (so the caller mutating their own object afterward — a real, separate risk — can't corrupt
the cache from the other direction either). `BufferPoolTest` has two tests that construct exactly
this scenario directly, at the lowest level where it's easiest to reason about, rather than
relying on it only being caught indirectly through `Session`.

## 7. What Phase 9 deliberately does not do

- **No configurable eviction policy.** LRU only — no clock/LFU/other strategies, which real
  buffer pools sometimes offer as tuning knobs.
- **No pinning.** A real buffer pool lets a caller "pin" a page it's actively using so it can't
  be evicted mid-operation. Every access here is a single, complete `readPage`/`writePage` call,
  so there's no window where a page needs to be protected from eviction mid-use — a reasonable
  scope boundary, not a gap, given how the rest of the system currently uses pages.
- **No partial/targeted flush.** `flushAll()` flushes every dirty page, not just the ones a
  specific transaction touched. Since (per section 5) nothing ever reaches the buffer pool
  except already-committed data, flushing everything is always correct — just occasionally a
  little more work than strictly necessary. Not worth the added bookkeeping yet.
- **Still no crash-safety during commit itself.** Flushing at commit restores durability for the
  "crash before or after commit" cases; a crash in the middle of `flushAll()` writing several
  pages is the same honestly-documented gap Phase 7 already named, now just sitting one layer
  further down. Still Phase 11's job.

## 8. What to look at in the code

- `src/main/java/com/strata/storage/page/Page.java` — gained `copy()` this phase.
- `src/main/java/com/strata/buffer/BufferPool.java` — the cache: LRU via `LinkedHashMap`,
  deferred dirty writes, and the defensive-copy fix.
- `src/main/java/com/strata/transaction/Session.java` — `Transaction`s now built on a shared
  `BufferPool`; `flushAll()` called right after every commit.
- `src/test/java/com/strata/buffer/BufferPoolTest.java` — 11 tests, including the two
  aliasing-bug regression tests described above.
- `src/test/java/com/strata/transaction/SessionBufferPoolTest.java` — 5 integration tests
  proving durability holds end-to-end through the buffer pool, including multiple sessions
  sharing one pool correctly seeing each other's commits.
