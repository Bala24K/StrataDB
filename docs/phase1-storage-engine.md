# Phase 1 — Storage Engine

Goal for this phase: `insert()`, `read()`, `delete()` for records — still with zero SQL involved.
A "record" here is just a `byte[]`; nothing in this layer knows or cares what's inside it. That's
on purpose (more on why below).

## 1. The problem this phase solves

Phase 0 gave us `Page` (a typed 4096-byte block) and `DiskManager` (page id ↔ byte offset on
disk). But a page is just raw bytes — it has no idea that it's supposed to hold, say, three
separate customer rows of different lengths. We need a *layout*: an agreed-upon way of carving
up a page's bytes so that "insert this record" and "give me record #2" are both well-defined,
efficient operations. That layout is called a **slotted page**, and it's what essentially every
disk-based relational database uses.

## 2. The slotted page layout

```
byte 0                                                              byte 4095
┌───────────┬────────┬────────┬────────┬─────────────┬───────┬───────┐
│  HEADER   │ slot 0 │ slot 1 │ slot 2 │  free space  │ rec 1 │ rec 0 │
└───────────┴────────┴────────┴────────┴─────────────┴───────┴───────┘
             └──── grows forward ────►               ◄─── grows backward ───┘
```

Three regions, two of them growing toward each other:

- **Header** (fixed, 12 bytes): how many slots exist, where free space currently starts, and
  a "next page" pointer (explained in section 5).
- **Slot directory**: one 8-byte entry per record — `(offset, length)` — that tells you where
  on the page that record's actual bytes live. Grows forward (toward higher offsets) every
  time a new record is inserted.
- **Record data**: the actual bytes of each record. Grows backward (toward lower offsets) from
  the end of the page.

Whatever's left between the two growing regions is free space — there's no separate "free list"
to maintain inside a single page; it's just whatever hasn't been claimed by either region yet.

Why grow the two regions toward each other instead of, say, both forward? Neither one knows in
advance how big it'll get. A page might end up holding 3 large records or 300 tiny ones. Growing
from opposite ends lets either region expand as needed without the two ever needing to know each
other's final size ahead of time.

## 3. Why a slot *index*, not a byte offset, is a record's address

This is the single most important idea in this phase. If code elsewhere in the database held on
to a record's raw byte offset, then any future operation that moves bytes around within a page
(compaction being the obvious one) would silently invalidate every reference to every record on
that page. Instead:

- Callers only ever refer to a record by **slot index** (0, 1, 2, ...).
- The slot directory entry for that index holds the *current* offset and length.
- If we ever rewrite where the actual bytes sit, we just update the slot directory entry — the
  slot index itself, and therefore everyone's reference to it, never changes.

`RecordId` (a new class this phase) captures this address as `(pageId, slotIndex)`. This is the
"pointer" type that Phase 0's docs foreshadowed — it's what a `RecordId`, and later a
`B+Tree`/hash index entry, will point at.

## 4. Delete = tombstone, not reclaim (a deliberate simplification)

`SlottedPage.deleteRecord(slot)` doesn't erase the record's bytes or shrink anything — it just
marks that slot's length as a sentinel value (`-1`, a "tombstone") meaning "this slot's record no
longer exists." The dead bytes stay exactly where they were.

This is deliberate, not sloppy: if delete *did* reclaim space by shifting other records around,
every other record's slot index on that page could change, breaking every `RecordId` anyone was
holding onto elsewhere (including, eventually, every index entry pointing at those records).
Tombstoning trades some wasted space for a much more important guarantee — a `RecordId`, once
handed out, stays valid until *that specific record* is deleted, no matter what else happens on
its page.

The wasted space is real, though, and it's explicitly out of scope for this phase. A later
**compaction** operation can safely reclaim it — as long as compaction only ever rewrites a
slot's *offset/length*, never the slot *index* itself, nothing built on top of this layer (an
index, a `RecordId` someone's holding) needs to know compaction happened at all.

## 5. Heap files: a table as a linked list of pages

A single page holds maybe a few dozen small records — nowhere near enough for a real table. We
need a table to span many pages. But we don't have a System Catalog yet (that's Phase 2) to
maintain "here's the list of pages table X owns."

The classic solution — and the one `HeapTable` uses — is to thread the pages together directly:
every page's header stores the id of the *next* page belonging to the same table
(`SlottedPage.getNextPageId()`), forming a singly linked list. A table then only needs to
remember one number, its **first page id**, to be able to find every page (and therefore every
record) it owns, by walking the chain. This kind of table — no particular record ordering,
just "wherever there was room" — is exactly what database textbooks call a **heap file**, which
is where the class name `HeapTable` comes from.

This also previews exactly what the Phase 2 Catalog needs to store per table: a name, and one
integer (the first page id). Nothing more, at this layer.

## 6. Why records are just `byte[]` right now

`HeapTable.insert(byte[])` doesn't know about columns, types, or a schema — on purpose. Baking
schema-awareness into the storage engine would tangle two genuinely separate concerns together:
"how do I pack variable-length blobs into fixed-size pages efficiently and durably" (this phase)
versus "how do I turn a row of typed columns into bytes and back" (the Catalog and, later, a
record serialization format in Phase 2). Keeping this layer schema-free means it can be fully
tested — and trusted — in complete isolation from anything SQL-shaped, and it means whatever
serialization format Phase 2 chooses can change freely without this layer ever needing to.

## 7. What Phase 1 deliberately does not do

Same spirit as the Phase 0 write-up — being explicit about scope keeps each phase honest:

- **No compaction** — see section 4. Deleted space isn't reclaimed yet.
- **No smarter free-space search.** `HeapTable.insert` walks the page chain from the start every
  time looking for room (first-fit, linear scan). Fine at this scale; a free-space map or a
  cached "last page with room" hint is a reasonable future optimization, not a correctness
  requirement.
- **No concurrency control.** Nothing here is safe to call from multiple threads at once yet —
  that's the Lock Manager's job (Phase 8).
- **No overflow pages.** A record larger than `SlottedPage.maxRecordSize()` (a bit under 4 KiB)
  is rejected outright rather than being split across multiple pages.

## 8. What to look at in the code

- `src/main/java/com/strata/storage/SlottedPage.java` — the header + slot directory + data
  layout described above, operating on one `Page` at a time.
- `src/main/java/com/strata/storage/RecordId.java` — the `(pageId, slotIndex)` address type.
- `src/main/java/com/strata/storage/HeapTable.java` — `insert`/`read`/`delete`/`scanAll` across
  a linked chain of `SlottedPage`s, built entirely on top of Phase 0's `DiskManager`.
- `src/test/java/com/strata/storage/SlottedPageTest.java` and `HeapTableTest.java` — 18 tests
  covering round trips, free-space accounting, tombstoning, bounds checking, multi-page spillover,
  scans that skip deleted records, persistence across a simulated restart, and the
  larger-than-a-page rejection case.
