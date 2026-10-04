# Architecture

## The stack

```
                        SQL Client / REPL
                                │
                        ┌───────▼────────┐
                        │  SQL Lexer     │  text  →  tokens
                        └───────┬────────┘
                                │
                        ┌───────▼────────┐
                        │  SQL Parser    │  tokens →  AST
                        └───────┬────────┘
                                │
                        ┌───────▼────────┐
                        │ Query Planner  │  AST →  execution plan
                        └───────┬────────┘
                                │
                        ┌───────▼────────┐
                        │ Query Executor │  plan →  rows
                        └───────┬────────┘
                                │
                ┌───────────────┼────────────────┐
                │               │                │
        ┌───────▼──────┐ ┌──────▼──────┐  ┌──────▼───────┐
        │ Transactions  │ │  Indexes    │  │ Buffer Pool  │
        └───────┬──────┘ └──────┬──────┘  └──────┬───────┘
                └───────────────┼────────────────┘
                                │
                        ┌───────▼────────┐
                        │ Storage Engine │  tables/records ↔ pages
                        └───────┬────────┘
                                │
                        ┌───────▼────────┐
                        │  Page Manager  │  page id ↔ byte offset
                        └───────┬────────┘
                                │
                          FileChannel / Disk
```

## Dependency direction

Dependencies only point **downward**. The Page Manager has never heard of a "table"; the
Storage Engine has never heard of "SQL"; the Executor has never heard of a network socket.
Each layer is a client of the interfaces exposed by the layer(s) below it, and nothing more.
This is what makes each module independently testable: `DiskManagerTest` doesn't need a SQL
parser to exist, and eventually `ExecutorTest` won't need a real disk — it'll run against an
in-memory fake of the storage interface.

## Module map (package → responsibility)

| Package (`com.strata.…`)     | Responsibility                                             | Status        |
|-------------------------------|-------------------------------------------------------------|---------------|
| `storage.page`                 | Fixed-size pages; page id ↔ byte offset; raw disk I/O       | ✅ Phase 0     |
| `storage` (records, tables)    | Tables/records laid out inside pages; free space tracking   | ✅ Phase 1     |
| `catalog`                      | System catalog: tables, columns, types, indexes             | ✅ Phase 2     |
| `lexer`                        | SQL text → token stream                                     | ✅ Phase 3     |
| `parser`                       | Token stream → AST                                          | ✅ Phase 4     |
| `executor` / `planner`         | AST → execution plan → rows                                 | ✅ Phase 5     |
| `index.hash`, `index.btree`    | Hash index, then B+Tree index                                | 🚧 Phase 6 (hash index done; B+Tree pending) |
| `transaction`                  | BEGIN/COMMIT/ROLLBACK, ACID bookkeeping                     | ✅ Phase 7     |
| `lock`                         | Shared/exclusive lock manager                                | ✅ Phase 8     |
| `buffer`                       | In-memory page cache with LRU eviction, dirty-page tracking  | ✅ Phase 9     |
| `optimizer`                    | Cost-based physical plan selection                            | ✅ Phase 10 (rule-based, not yet cost-based) |
| `recovery`                     | Write-ahead log, crash recovery                              | ✅ Phase 11    |
| `network`                      | TCP server, wire protocol, multiple clients                  | ✅ Phase 12    |
| `transaction`                  | BEGIN/COMMIT/ROLLBACK, ACID bookkeeping                     | ⏳ Phase 7     |
| `lock`                         | Shared/exclusive lock manager                                | ⏳ Phase 8     |
| `buffer`                       | In-memory page cache with LRU eviction, dirty-page tracking  | ⏳ Phase 9     |
| `optimizer`                    | Cost-based physical plan selection                          | ⏳ Phase 10    |
| `recovery`                     | Write-ahead log, crash recovery                              | ⏳ Phase 11    |
| `network`                      | TCP server, wire protocol, multiple clients                  | ⏳ Phase 12    |
| `security`                     | Users, roles, permissions                                    | ⏳ Phase 13    |

Later phases (replication, distribution, ML-assisted optimization) build on top of everything
above and aren't part of the initial module map — see `docs/ROADMAP.md`.

## Interface philosophy

Every module exposes the smallest interface that the layer above actually needs, and hides
everything else. Concretely, for Phase 0:

- `Page` — a typed view over exactly `PAGE_SIZE` bytes. Knows about offsets and primitive
  types. Knows *nothing* about files.
- `DiskManager` — the only class allowed to touch `FileChannel`. Exposes `readPage(id)`,
  `writePage(id, page)`, `allocatePage()`, `getNumPages()`. Everything above it works purely
  in terms of integer page ids, never file offsets directly.

And for Phase 1:

- `SlottedPage` — interprets one `Page`'s bytes as header + slot directory + record data.
  Knows about slots and free space. Knows *nothing* about tables spanning multiple pages.
- `RecordId` — a stable `(pageId, slotIndex)` address, independent of how bytes are physically
  arranged within a page.
- `HeapTable` — chains `SlottedPage`s together into a table via a linked list of pages.
  Exposes `insert(byte[])`, `read(RecordId)`, `delete(RecordId)`, `scanAll()`. Knows *nothing*
  about what's inside a record's bytes — no columns, no types, no SQL.

And for Phase 2:

- `TableInfo` — one table's schema (name, columns, and the first page id of its data), with a
  self-contained binary (de)serialization format for storing it as a record.
- `Catalog` — remembers every table's schema and where its data lives, persisted as a
  `HeapTable` of `TableInfo` records rooted at a well-known page (page 0) in every database
  file. Exposes `createTable`, `getTable`, `tableExists`, `listTableNames`, and `openTable`
  (the bridge back down to a working `HeapTable` for that table's rows).

And for Phase 3:

- `Lexer` — turns raw SQL text into a `List<Token>`. Knows about characters, keywords, and
  literals. Knows *nothing* about SQL grammar or statement structure — that's the parser's job.
- `Token` / `TokenType` — one lexical unit and its category, with position info for error
  messages later.

And for Phase 4:

- `Statement` / `Expr` — a sealed-interface, record-based AST covering `CREATE TABLE`,
  `INSERT`, `UPDATE`, `DELETE`, and `SELECT`. Knows about SQL grammar structure. Knows
  *nothing* about the catalog, storage, or execution — it's a pure data description of "what
  did the user ask for," produced once and handed to whatever consumes it next.
- `Parser` — recursive-descent, tokens in, a `Statement` out. Operator precedence for
  `WHERE`-clause expressions falls out of which parsing method calls which, not an explicit
  precedence table.

And for Phase 5 — the point every earlier module finally meets:

- `Row` / `RowSerializer` — a schema-typed row, and its binary (de)serialization, finally
  giving `HeapTable`'s storage a real record layout instead of opaque bytes.
- `Executor` — runs a `Statement` against a `Catalog` and its tables. There's no separate
  Planner yet (see the Phase 5 docs for why): without joins or indexes, a full table scan is
  the only possible execution strategy, so `Executor.execute()` doubles as the plan.

And for Phase 6 (in progress):

- `HashIndex` / `EntryCodec` — a persistent static hash index over `(key, RecordId)` pairs.
  Each bucket is literally a `HeapTable`, reusing Phase 1's page-chaining for overflow. Not yet
  wired into the catalog, SQL, or the executor — see the Phase 6 docs for the reasoning and for
  what's still open (dynamic resizing, the B+Tree).

And for Phase 7:

- `PageStore` — extracted from `DiskManager`: the interface `HeapTable`/`Catalog` actually
  depend on, which is what let `Transaction` slot underneath them with zero changes to either.
- `Transaction` — a `PageStore` that buffers writes in memory; `commit()` applies them all at
  once, `rollback()` discards them.
- `Session` — routes statements to an explicit transaction (between `BEGIN` and `COMMIT`/
  `ROLLBACK`) or a fresh autocommit one. `Executor` itself refuses to run `BEGIN`/`COMMIT`/
  `ROLLBACK` — transaction lifecycle is a session-level concern, not an execution-level one.

And for Phase 8:

- `LockManager` — shared/exclusive locks over named resources (table names, as `Session` uses
  it), built on plain Java monitors, with timeout-based deadlock handling. `Session` acquires
  the right lock before running each statement and releases everything at `COMMIT`/`ROLLBACK` —
  strict two-phase locking.

And for Phase 9:

- `BufferPool` — a third implementation of `PageStore` (after `DiskManager` and `Transaction`):
  an in-memory cache with LRU eviction and deferred, dirty-page write-back, sitting between
  every `Transaction` and the real `DiskManager`. `Session` calls `flushAll()` right after every
  commit to keep Phase 7's durability guarantee intact (the NO-STEAL/FORCE policy — see the
  Phase 9 docs).

And for Phase 10:

- `IndexInfo` — a persisted index's metadata, now tracked in `Catalog` alongside `TableInfo`.
- `QueryPlanner` / `ScanPlan` — decides `FullScan` vs. `IndexScan` for a table access, based on
  whether `WHERE` has a usable equality on an indexed column. `Executor` consults it for every
  `SELECT`/`UPDATE`/`DELETE`, and maintains every table's indexes on every write.

And for Phase 11:

- `PageStores` — the shared "apply a batch of writes, growing the store as needed" logic,
  factored out of `Transaction` so WAL recovery can reuse the exact same behavior.
- `WriteAheadLog` — an append-only, checksummed log of committed transactions' page writes,
  fsynced before `Transaction.commit()` applies anything to the real store. `Session.open()`
  replays it before anything else touches the database file.

And for Phase 12:

- `DatabaseServer` / `DatabaseClient` / `Protocol` — a thread-per-connection TCP server; each
  connection becomes its own `Session`, sharing the database's `BufferPool`/`LockManager`/
  `WriteAheadLog` exactly as multi-session tests since Phase 8 already assumed.

As we go up the stack, each new module (`buffer.BufferPool`, `storage.Table`, ...) will
depend on the *interface* of the module below, not its implementation details — this is what
lets us swap the Phase-0 "always fsync, no caching" `DiskManager` for a cached, WAL-backed
version in Phase 9/11 without rewriting anything above it.

## Testing philosophy

Every module gets unit tests that exercise it in isolation (see `src/test/java`, mirroring
`src/main/java` package-for-package). As the stack grows, we'll add integration tests that
exercise multiple layers together (e.g. "insert a row through SQL, verify the bytes on disk"),
but the unit tests are what let each phase be developed and trusted independently.
