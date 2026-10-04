# Phase 6 (part 1) — Hash Index

Goal for this part of the phase: a real, persistent index — search, insert, delete over
`(key, RecordId)` pairs — starting with a **hash index**, as the project plan named it. This
document covers what's built so far; see section 6 for exactly what's still open in this phase.

## 1. What an index is for (and what it isn't doing yet)

Every `SELECT ... WHERE id = 5` so far has meant "read every row in the table and check each
one" — a full table scan, the only access path that's existed since Phase 1. An index is a
second, purpose-built structure that answers "which rows have this value?" directly, without
looking at the rest of the table.

**Important scope note:** building the index is not the same as using it. `Executor` (Phase 5)
doesn't know `HashIndex` exists yet, and there's no `CREATE INDEX` in the SQL grammar (Phase 4).
Every `SELECT` still does a full scan, index or no index. Teaching the executor to actually
*choose* between a scan and an index lookup is a genuine query-planning decision — that's
Phase 10's job, once there's finally more than one possible way to answer a query. This phase
is about building the data structure itself, correctly and independently, the same way Phase 1
built `HeapTable` before Phase 5 ever used it for anything.

## 2. Static hashing, not dynamic — and why

There are two broad families of hash index: **static hashing**, where the number of buckets is
fixed and a bucket that fills up just grows a longer overflow chain, and **dynamic hashing**
(extendible or linear hashing), where the number of buckets grows and shrinks with the data,
splitting and merging buckets to keep them roughly evenly loaded. The original project plan's
"Split"/"Merge" language points at the dynamic version.

`HashIndex` implements static hashing with overflow chaining — this is a real, complete, widely
taught technique in its own right (it's how *Database System Concepts*, one of the books named
in the original plan, introduces hashing before extendible hashing), not a shortcut version of
something else. `numBuckets` is fixed at creation time. A bucket that receives a lot of entries
simply keeps growing its own page chain rather than triggering a rebalance. For a teaching-scale
index, this degrades gracefully rather than breaking; a workload where bucket sizes get wildly
uneven is exactly the case dynamic hashing exists to handle, and that stays open for a future
increment of this phase.

## 3. The one big trick: a bucket is just a `HeapTable`

A hash bucket needs exactly one thing: somewhere to insert, scan, and delete an open-ended
number of small variable-length records, growing across more pages as it fills. That's *exactly*
what `HeapTable` already does. So each bucket **is** a `HeapTable` — its "records" are
`(key, RecordId)` pairs encoded by `EntryCodec`, and `HashIndex.insert`/`search`/`delete` are
thin wrappers around `HeapTable.insert`/`scanAll`/`delete`.

The payoff: a bucket that outgrows its first page overflows into more linked pages completely
for free, using the exact same page-chaining mechanism `HeapTable` got in Phase 1. Nothing new
had to be built for that — `testBucketGrowsPastOnePageViaOverflowChaining` inserts 500 entries
into a single-bucket index and confirms both that the bucket actually spans multiple pages *and*
that every entry, including ones on an overflow page, is still found by `search`.

## 4. The "directory": every bucket found from two integers

`HashIndex.create` allocates all `numBuckets` bucket-root pages in one tight loop. Since nothing
else can allocate a page in between (there's no concurrency yet — same caveat every phase so
far has carried), those pages come out with **contiguous page ids**, by construction. That means
bucket `i`'s root page is always `directoryFirstPageId + i` — no separate directory structure
needs to be stored anywhere. The whole index can be reopened from exactly two numbers:
`getDirectoryFirstPageId()` and `getNumBuckets()`. This is the same trick `Catalog` used with its
own page-0 convention in Phase 2, applied again: a fixed, predictable numbering scheme replaces
what would otherwise need to be its own persisted lookup structure.

## 5. A real bug avoided: hashing must not depend on how a number is boxed

This is worth understanding carefully, because it's a genuine correctness trap, not a
hypothetical one. `EntryCodec` lets a `BIGINT`-typed index accept a boxed `Integer` as well as a
`Long` for convenience — widening an `int` to a `long` never loses information, so it's a safe
relaxation. But if bucket selection used `key.hashCode()` directly, that convenience would be
dangerous: `Integer.hashCode()` and `Long.hashCode()` are **not guaranteed to produce the same
value for the same number** in general (their formulas differ — `Long.hashCode` folds the upper
and lower 32 bits of the value together). If `Integer.valueOf(1234)` and `Long.valueOf(1234L)`
ever hashed to *different* buckets, `search(1234L)` could miss an entry that was inserted as
`1234`, silently — which would violate the one invariant a hash structure absolutely cannot
break: **equal keys must always land in the same bucket.**

`HashIndex.hashKey()` avoids this by normalizing every `Number` through
`Long.hashCode(value.longValue())` *before* hashing, rather than trusting each boxed type's own
`hashCode()`. `testIntegerAndLongKeysOfTheSameValueHashToTheSameBucket` inserts as an `Integer`
and searches as a `Long` for the same value, specifically to pin this down.

## 6. What this phase still has open

- **No dynamic resizing.** Covered in section 2 — static hashing only, for now.
- **No B+Tree yet.** A hash index only ever answers exact-match lookups ("give me the row(s)
  with exactly this key") — it has no notion of order, so it's no help for a range condition
  like `balance > 100`, or for satisfying an `ORDER BY` without a separate sort. A B+Tree is
  what an ordered index buys you, and is the next piece of this phase.
- **Not wired into the catalog or SQL.** No `CREATE INDEX` statement, no index metadata stored
  anywhere durable yet — `HashIndex.create`/`open` are called directly from Java (as the tests
  do), not driven by SQL. Persisting index metadata (which column, which table, the two numbers
  from section 4) is natural future work for the catalog, following the same tagged-entry
  pattern `TableInfo` already established — deliberately not built until there's an actual
  `CREATE INDEX` statement that would need it.
- **Not consulted by the executor.** Reiterating section 1's point since it's the most important
  one: building this structure doesn't speed up a single existing query yet. That wiring is
  Phase 10's job.

## 7. What to look at in the code

- `src/main/java/com/strata/index/hash/EntryCodec.java` — encodes/decodes one
  `(key, RecordId)` entry; package-private, an implementation detail of `HashIndex`.
- `src/main/java/com/strata/index/hash/HashIndex.java` — the index itself: bucket-as-`HeapTable`,
  the contiguous directory, and the hashing-consistency fix.
- `src/test/java/com/strata/index/hash/HashIndexTest.java` — 12 tests covering insert/search/
  delete round trips, duplicate keys, bucket collisions, overflow chaining past a single page,
  the `Integer`/`Long` hashing guarantee, and persistence across a restart.
