# Phase 0 — Storage Fundamentals

Goal for this phase: be able to read and write fixed-size binary pages to a file. That's it —
no records, no tables, no free-space tracking. Everything else in the database is eventually
built on top of this, so it's worth being slow and precise here.

## 1. A file, from the database's point of view

To your OS, a file is a named, resizable sequence of bytes on disk. To *us*, that's still too
coarse a unit to work with directly — reading "byte 4,000,001" one byte at a time, or growing
the file one byte per insert, would be both awkward to program against and (much more
importantly) slow, because disks don't actually deliver "one byte" — they deliver whole
blocks/sectors at a time no matter how much of that block you actually asked for. If you're
going to pay for a whole block anyway, you may as well organize your data in block-sized units
on purpose. That unit is called a **page**.

## 2. What a page is, and why 4096 bytes

A page is a fixed-size chunk of bytes — in StrataDB, `Page.PAGE_SIZE = 4096` (4 KiB). Every
single page in a StrataDB file is exactly this size, always, forever. That fixed-size-ness is
what makes the whole system simple: if you know a page's *number*, you automatically know
exactly where it lives in the file, with no lookup table required (more on that in a moment).

4096 was not a random choice. It's the standard virtual memory page size on most operating
systems, and a very common filesystem block size. Lining our page size up with the OS's own
unit of I/O means a single page read or write from us tends to correspond to a single,
efficient unit of work for the OS and disk, rather than us asking for something that spans two
OS pages (wasteful) or is smaller than one (also wasteful, since the OS fetches the whole page
either way).

There's a real trade-off buried in this choice that's worth naming even though we're not
tuning it yet: bigger pages amortize per-I/O overhead better and hold more rows per page, but
waste more space when a page is mostly empty, and make each individual I/O more expensive.
4 KiB is a conservative, well-understood default; later phases (buffer pool, indexes) are
exactly where this choice starts to matter more.

## 3. Addressing: page id → byte offset

Because every page is exactly `PAGE_SIZE` bytes, finding page `n` in the file needs no index,
no lookup — just arithmetic:

```
byteOffset = pageId * PAGE_SIZE
```

That single formula (see `DiskManager.toByteOffset`) *is* the address space of a StrataDB
database file. Page 0 lives at byte 0. Page 1 lives at byte 4096. Page 41 lives at byte
167,936. This will become the foundation for every higher-level "pointer" we build later — a
record id will eventually be something like `(pageId, slotNumber)`, and resolving it will still
bottom out in this same formula.

## 4. Random access, not streaming

Notice that nothing about a page assumes you read the file from the start. We want to jump
straight to "page 41" without reading pages 0 through 40 first. This is why `Page` exposes
*offset-based* accessors (`getInt(offset)`, `putBytes(offset, ...)`) instead of
stream-style sequential reads — pages are memory-like (randomly addressable), not stream-like.

## 5. `ByteBuffer`: turning bytes into typed values

A page is fundamentally just a `byte[4096]`. But we obviously want to store things like
integers and longs inside it, not manually shift-and-mask bytes by hand every time. That's
what `java.nio.ByteBuffer` is for — it's a thin, typed wrapper around a byte array that knows
how to translate between "4 bytes starting at offset 100" and "the int value 42".

Two `ByteBuffer` details matter for how `Page` uses it:

- **Absolute vs. relative access.** `ByteBuffer` has both a "cursor-based" relative API
  (`putInt(value)` advances an internal position) and an *absolute* API
  (`putInt(offset, value)`, which ignores the cursor entirely). `Page` only ever uses the
  absolute form, because — same as point 4 above — we're doing random access into a page's
  contents, not writing it sequentially from front to back.
- **Byte order (endianness).** A multi-byte value like an `int` can be stored
  most-significant-byte-first (*big-endian*) or least-significant-byte-first
  (*little-endian*). `ByteBuffer` defaults to big-endian, which is also the conventional
  "network byte order" — we keep that default so the same convention will carry cleanly into
  the network module much later, instead of having two different byte orders floating around
  the codebase.

## 6. `FileChannel`: talking to the actual file

`Page` never touches the filesystem — that's `DiskManager`'s job, and specifically
`java.nio.channels.FileChannel`'s job.

You might reasonably ask: Java has had `FileInputStream`/`FileOutputStream` forever, why not
just use those? The answer is in how they're shaped. A stream carries one internal cursor —
you seek, then read, and the two operations aren't atomic with respect to each other. That's a
fine model for "read this file top to bottom" but a bad fit for "give me page 41, then page 3,
then page 41 again" from potentially multiple threads at once.

`FileChannel` instead offers **positional I/O**:

```java
channel.read(buffer, position);   // read at `position`, doesn't touch/need any shared cursor
channel.write(buffer, position);  // write at `position`, same deal
```

Every read or write states its own byte offset explicitly. There's no shared "current
position" to race on. This is exactly the shape our `pageId → byteOffset` addressing scheme
wants, which is why `DiskManager.readPage`/`writePage` are just thin wrappers around these two
calls (see `readFully`/`writeFully` in `DiskManager` — they loop because the OS is technically
allowed to do a "short" read/write of fewer bytes than requested, so we keep going until the
whole page has actually moved).

## 7. A note on durability (and a preview of later phases)

When `channel.write(...)` returns, has your data survived a power cut? Not necessarily — the
OS typically buffers writes in its own page cache and flushes them to the physical disk on its
own schedule, for performance. `FileChannel.force(boolean)` is how you tell the OS "stop
buffering, actually flush this to stable storage now" (this is the same concept as the Unix
`fsync` system call).

`DiskManager.writePage` calls `force(false)` after every single write. That's the *correct*
but *slow* answer — every page write pays the full cost of a physical flush. Real databases
don't do this for every write; they batch writes and rely on a **write-ahead log** to
guarantee durability more cheaply, replaying the log if the process crashes before the "real"
data made it to disk. We're calling that out now on purpose: Phase 9 (Buffer Pool) and
Phase 11 (Recovery/WAL) exist specifically to replace this naive "fsync on every write" with
that smarter strategy — without changing anything about the `readPage`/`writePage` contract
that the rest of the system depends on.

## 8. What Phase 0 deliberately does *not* do

To keep this phase honest to its stated deliverable ("read/write binary pages, nothing else"):

- No concept of a "record" or "row" inside a page yet — that's Phase 1.
- No free-space tracking or page deletion — pages are only ever allocated, never freed, for
  now.
- No caching — every `readPage`/`writePage` goes straight to disk. The Buffer Pool phase adds
  an in-memory cache in front of exactly this interface, later.
- No concurrency control beyond the `synchronized` methods on `DiskManager` needed to keep its
  own bookkeeping (`numPages`) consistent — real concurrent access control is the Lock
  Manager's job (Phase 8).

## 9. What to look at in the code

- `src/main/java/com/strata/storage/page/Page.java` — the fixed-size, typed byte block.
- `src/main/java/com/strata/storage/page/DiskManager.java` — page id ↔ byte offset, and the
  actual `FileChannel` calls.
- `src/test/java/com/strata/storage/page/PageTest.java` and `DiskManagerTest.java` — 13 tests
  covering round-trips for every type, bounds checking, isolation between pages, and
  persistence across closing/reopening the file (simulating a process restart).
