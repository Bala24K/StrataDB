# Phase 7 — Transactions

Goal for this phase: `BEGIN`, `COMMIT`, `ROLLBACK` — and, more importantly, actually
understanding which of Atomicity, Consistency, Isolation, and Durability each one buys you,
and which ones this phase still doesn't.

## 1. The problem: every write so far has been immediate and irreversible

Every phase up to now wrote straight through to disk. `HeapTable.insert()` calls
`pageStore.writePage()`, which (via `DiskManager`) writes the bytes and calls `force()` — an
fsync — immediately. There was no way to "undo" an `INSERT`, because by the time the method
returned, the old bytes were already gone from disk. `BEGIN`/`COMMIT`/`ROLLBACK` can't mean
anything until there's a way to make a batch of writes provisional — visible to whoever's making
them, invisible to everyone else, discardable — until a `COMMIT` says "make it permanent."

## 2. Why Phase 6's `PageStore` extraction turned out to matter here

Phase 6 pulled `PageStore` (`readPage`/`writePage`/`allocatePage`/`getNumPages`) out of the
concrete `DiskManager` class, purely so `HeapTable` and `Catalog` would depend on an interface
instead of a specific implementation. At the time, the immediate reason was tidiness. This phase
is where that decision actually pays rent: `Transaction` implements `PageStore` too — so
`HeapTable.open(txn, ...)` and `Catalog.open(txn)` work completely unmodified. Neither class
changed by a single line for transactions to exist. `TransactionTest` proves this directly:
it opens a real `HeapTable`/`Catalog` against a `Transaction`, and everything works exactly as
it did against a raw `DiskManager` in every earlier phase's tests.

This is the "clean interfaces, dependencies point downward" principle from the very start of
this project, showing up as a concrete, checkable payoff rather than just a design slogan.

## 3. What `Transaction` actually does

`Transaction` is a `PageStore` that buffers every write in an in-memory `Map<Integer, Page>`
instead of touching the real store:

- **`readPage`** checks its own buffer first — if this transaction already wrote page 7, it
  reads back *that* version, not what's on disk. Anything it hasn't touched falls through to
  the real store, so a transaction always sees the latest *committed* state for everything else,
  plus its own uncommitted changes layered on top.
- **`writePage`/`allocatePage`** only ever touch the in-memory buffer.
- **`commit()`** replays every buffered page through the real store, in ascending page-id order,
  and returns.
- **`rollback()`** just clears the buffer. Since nothing was ever written to the real store,
  there's nothing to undo.

## 4. Where each ACID property actually comes from

This is worth being precise about, rather than just claiming "transactions work now":

- **Atomicity** (all of a transaction's writes take effect, or none do) comes directly from
  buffering. Nothing is real until `commit()` replays it.
- **Isolation** (uncommitted changes aren't visible to others) comes from the same buffering:
  another `Transaction` (or a direct read against the real store) only ever sees committed
  state, because uncommitted writes live in a buffer that only *this* transaction can see.
  `SessionTest.testExplicitTransactionIsInvisibleUntilCommitted` and `TransactionTest`'s
  `Catalog`/`HeapTable` integration tests check this directly, by opening a *second*,
  independent `Transaction` against the same underlying store mid-transaction and confirming it
  sees nothing.
- **Durability** (a committed transaction survives a crash) comes from a decision made all the
  way back in **Phase 0**: `DiskManager.writePage()` fsyncs on every single write. The instant
  `commit()` finishes replaying a transaction's buffer through the real store, those pages
  aren't just written — they're already flushed to stable storage. Nothing new had to be built
  for durability this phase; it was already sitting there, three phases early.
- **Consistency** (the database only ever moves between states that satisfy its own rules)
  mostly falls out of what `Executor` and `Catalog` were already enforcing — type coercion,
  known-table/known-column checks, the arity checks on `INSERT`/`UPDATE`. There isn't a separate
  "consistency mechanism" to point at; it's the sum of validation everywhere else, which
  atomicity now protects as a whole unit rather than statement-by-statement.

## 5. The honest gap: no crash-safety exactly *during* commit

If the process crashes in the middle of `commit()` — after some buffered pages have been written
through but before all of them — the database is left in a state that's neither fully the old
version nor fully the new one. A production system closes this with a **Write-Ahead Log**: write
the whole transaction's intent durably first, in one place, then apply it; a crash mid-apply can
always be finished (or safely ignored) by replaying the log on restart. That's Phase 11's job.
This phase's `Transaction` gives real atomicity and isolation for the overwhelmingly common case
— no crash exactly mid-commit — which is genuinely useful on its own, and is exactly the honest
boundary of what's built so far.

## 6. `Session`: why `Executor` itself refuses `BEGIN`/`COMMIT`/`ROLLBACK`

Adding `Begin`/`Commit`/`Rollback` to the sealed `Statement` hierarchy forced `Executor.execute()`
to handle them somewhere, since Java requires an exhaustive `switch` over a sealed type. Its
answer is to throw, on purpose: `Executor` only ever knows how to run statements against *one
fixed* `Catalog`. Deciding *which* `Catalog` — which transaction — a statement should run against
is a session-level question, not something `Executor` should need to know about.

`Session` is the layer that actually owns that decision:

- With no open transaction, every statement gets its own brand-new `Transaction`, runs, and
  commits immediately — **autocommit**, the same default most real databases use outside an
  explicit transaction block.
- After `BEGIN`, every subsequent statement runs against the *same* `Transaction`/`Catalog`/
  `Executor` trio, until `COMMIT` or `ROLLBACK` ends it.

One deliberately-noted inefficiency: every autocommit statement, and every `BEGIN`, opens a
brand-new `Catalog`, which rescans the entire catalog table from scratch. That's correct (it
always reflects the latest committed state) but wasteful at real scale — a production system
would cache the catalog and invalidate it only when DDL changes it. Not worth building until
it's actually slow.

Nested transactions (`BEGIN` while already inside one) are rejected outright — there's no
`SAVEPOINT` mechanism to make nesting meaningful, so pretending to support it would be worse
than refusing it clearly.

## 7. What Phase 7 deliberately does not do

- **No crash-safety during commit itself** — see section 5. Phase 11's job.
- **No concurrency.** Exactly one transaction should be active against a given store at a time.
  `Transaction.commit()` defensively checks that the underlying store's page count hasn't
  drifted since the transaction began, so an obviously-violated assumption fails loudly instead
  of silently corrupting data — but nothing *coordinates* multiple simultaneous transactions.
  That's the Lock Manager, Phase 8.
- **No isolation *levels*.** There's exactly one isolation behavior here (roughly: read
  committed, since every read either comes from this transaction's own buffer or the latest
  committed state) — no repeatable-read or serializable options, which real systems offer as
  configurable trade-offs between correctness and concurrency.
- **No `SAVEPOINT`s**, so no nested or partial rollbacks within one transaction.

## 8. What to look at in the code

- `src/main/java/com/strata/storage/page/PageStore.java` — the interface extracted this phase
  (from `DiskManager`) that makes `Transaction` possible.
- `src/main/java/com/strata/transaction/Transaction.java` — the buffering `PageStore`.
- `src/main/java/com/strata/transaction/Session.java` — routes statements to an explicit
  transaction or a fresh autocommit one.
- `src/main/java/com/strata/parser/ast/Statement.java` — `Begin`/`Commit`/`Rollback`, added
  this phase.
- `src/main/java/com/strata/executor/Executor.java` — deliberately refuses to run those three.
- `src/test/java/com/strata/transaction/TransactionTest.java` and `SessionTest.java` — 24 new
  tests (160 total), including `HeapTable`/`Catalog` used directly through a `Transaction` with
  zero code changes to either, a full simulated "transfer between two accounts" workflow, and a
  second independent transaction confirming isolation against the same underlying store.
