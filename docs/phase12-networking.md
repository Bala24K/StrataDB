# Phase 12 — Networking

Goal for this phase: a TCP server, so StrataDB can finally be talked to by more than one
independent process at once — real multi-client support, not just multiple `Session` objects
sharing one process (which is all every earlier phase's "multi-user" tests actually exercised).

## 1. Why this phase was mostly plumbing, not new database logic

Every piece this phase needed already existed. Phase 8's `LockManager` and Phase 9's
`BufferPool` were both explicitly built to coordinate multiple `Session`s sharing one database;
Phase 11's `WriteAheadLog` was built the same way. The only genuinely new idea in this phase is
that a `Session` no longer has to live in the same process as whoever's using it — each accepted
TCP connection just becomes its own `Session`, constructed against the exact same shared
`BufferPool`/`LockManager`/`WriteAheadLog` every earlier multi-session test already used.
`DatabaseServer` is a thread-per-connection TCP listener wrapped around that one idea; nothing
about how SQL actually runs changed at all.

## 2. The protocol: simple and readable, with an explicit, named trade-off

`Protocol` is a line-oriented text format: one SQL statement per line in, a few lines of
plain-text response out, always ending in a literal `END` line so a line-reading client knows
where a response finishes on a byte stream with no other message boundaries. You can drive it
with nothing more than `nc localhost <port>` — no client library required, which is exactly the
point for a project like this one.

That simplicity has a real cost, named directly rather than glossed over: values are rendered
with plain `toString()`, and `NULL` is written as the literal text `NULL`. A `VARCHAR` value that
happens to *be* the text `"NULL"`, or that happens to contain a tab character, is genuinely
ambiguous on the wire. Production protocols (PostgreSQL's, for instance) avoid this with typed,
length-prefixed binary fields. That's real, valuable future work, deliberately traded away here
for a protocol simple enough to read by eye.

## 3. One thread per connection, not an event loop — and why that's a legitimate choice

`DatabaseServer` uses classic blocking I/O: `serverSocket.accept()` in a loop, spawning a new
daemon thread per connection. This is not the most scalable design a network server could use —
an NIO event loop handles vastly more simultaneous connections with far less memory overhead.
But thread-per-connection is simpler to read, simpler to reason about, and completely correct
for a project at this scale. Building the more scalable version is real future work, not a
correctness gap in what's here.

## 4. The bug this phase actually found: a serious, latent flaw in Phase 8's `LockManager`

This is worth walking through carefully, because it's the most serious bug found in this entire
project, and it had been sitting in working code since Phase 8 — every earlier concurrency test
happened to pass despite it.

`LockManager.acquire()` fetches a resource's `LockState` object once, at the very top, and reuses
that same reference throughout its `wait()` loop. The original `releaseAll()` removed a
resource's entry from the `locks` map entirely once nothing held it anymore — a reasonable-
looking memory optimization. It was, in fact, a serious correctness bug: a thread already
*waiting* on that `LockState` object when it got removed from the map would still be granted the
lock by mutating that now-orphaned object — completely invisible to the map, which would
`computeIfAbsent` a brand-new, independent `LockState` for the very next unrelated `acquire()`
call on the same resource name. Two transactions could each walk away believing they held an
exclusive lock on the same table at the same time.

**Why every earlier test missed this.** Phase 8's own concurrency tests used at most two or three
threads and carefully coordinated interleavings via `CountDownLatch`es — exactly the kind of test
that proves individual lock/release transitions work correctly, but doesn't happen to exercise
the specific sequence needed to orphan a `LockState`: a resource has to go fully unheld (removed
from the map) *while* another thread is already parked waiting on it. That needs enough
concurrent contention that a "someone already waiting when the last holder releases" race
actually happens — which eight real socket-connected clients, hammering one exclusive lock at
once in `testManyConcurrentClientsInsertingDoNotLoseWrites`, was finally enough to trigger,
reliably, every time.

**What it looked like when it broke.** Not a crash, and not obviously "8 threads all failed" —
a handful of clients succeeded almost instantly, and every remaining client hung until its lock
timeout and then failed with `LockTimeoutException`, consistently and repeatably. That shape —
a few fast successes, then everyone else stuck until timeout — is the signature of later waiters
parked on a `LockState` that nothing could ever correctly clear again, not ordinary queueing
delay (which would show a spread of completion times, not a bimodal split).

**The fix.** Never remove a `LockState` from the map once created. The map ends up holding one
permanent entry per resource ever locked — a trivial memory cost given how few distinct
resources (table names) a real StrataDB workload has — and it's exactly what guarantees every
`acquire()` for the same resource, no matter when it runs, always shares the one true, live
`LockState` object.

**Why this is worth dwelling on.** It's a clean example of a class of bug that's genuinely hard
to catch without exactly the right kind of test: individually correct-looking operations (a
timely removal to save memory; a wait loop that reuses its captured state) combine to produce a
race that only manifests under real, sustained, multi-way contention. Lighter-weight concurrency
tests — even carefully-written ones — can pass for phases at a time while a bug like this sits
latent, waiting for a workload that actually exercises the specific interleaving it depends on.

## 5. What Phase 12 deliberately does not do

- **No connection pooling or resource limits.** A client that connects and never disconnects
  holds a thread (and a `Session`) for as long as the connection lives; there's no cap on
  concurrent connections.
- **No authentication.** Anyone who can open a TCP connection to the port can run any statement.
  That's explicitly Phase 13's job.
- **No structured/binary wire format**, as covered in section 2 — real, valuable future work.
- **No TLS.** All traffic, including any future authentication credentials, would travel
  unencrypted. Also explicitly future work, alongside Phase 13.

## 6. What to look at in the code

- `src/main/java/com/strata/network/Protocol.java` — the wire format.
- `src/main/java/com/strata/network/DatabaseServer.java` — the TCP server: one `Session` per
  connection, sharing the database's `BufferPool`/`LockManager`/`WriteAheadLog`.
- `src/main/java/com/strata/network/DatabaseClient.java` — a minimal client, deliberately small.
- `src/main/java/com/strata/lock/LockManager.java` — the orphaned-`LockState` fix from section 4,
  documented directly in the class's own javadoc.
- `src/test/java/com/strata/network/DatabaseServerTest.java` — 10 tests, including the one that
  found the bug, plus basic request/response, error handling, transactions over the wire, lock
  contention between two networked clients, and persistence across a full server restart.
