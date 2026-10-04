# Phase 2 — System Catalog

Goal for this phase: the database remembers what tables exist and what columns they have —
across restarts, with no SQL involved yet.

## 1. What a catalog is, and why every DBMS has one

Ask a database "what tables do you have, and what columns does `users` have?" and it has to
answer from *somewhere*. That somewhere is the **system catalog** — metadata about the
database's own structure, stored durably, just like any other data. Every real relational
database has one: Postgres's catalog tables are named things like `pg_class` and
`pg_attribute`; MySQL exposes its version as `information_schema`. Ours is `Catalog`.

## 2. The bootstrapping problem

Here's the puzzle this phase actually revolves around: a catalog needs to remember a list of
tables *durably* — which sounds exactly like a job for `HeapTable`, which we already have. But
`HeapTable.open()` requires you to already know its first page id... which is itself
information that would need to live in a catalog. Which we don't have yet, because we're trying
to build it. That's circular.

Real databases solve this the same way filesystems solve the analogous problem (a filesystem
needs to find its own root directory before it can look anything up) — with a **convention**
instead of a lookup: some fixed, well-known location that every instance agrees on ahead of
time, so bootstrapping has somewhere to end.

For StrataDB, that convention is `Catalog.CATALOG_ROOT_PAGE_ID = 0`. **Page 0 of every StrataDB
file is always the first page of the catalog's own table.** No exceptions, nothing stored to
confirm it — it's just always true by construction, the same way a filesystem superblock always
lives at a fixed offset rather than being found via some other directory.

This works out for free given what we already built: `DiskManager` hands out page ids
sequentially starting at 0, and `HeapTable.create()` allocates its first page as the very first
thing it does. So the very first `HeapTable.create()` call ever made against a brand-new file is
*guaranteed* to land on page 0. `Catalog.bootstrap()` relies on exactly that guarantee (and
double-checks it, so a violated invariant fails loudly rather than corrupting data silently).

## 3. The catalog is a table about tables

Once you have a fixed starting point, the rest stops being special: the catalog is implemented
as an ordinary `HeapTable` whose records happen to be serialized `TableInfo` objects — one row
per user table, holding that table's name, its schema, and the first page id of *its* data.
Creating a new user table means: allocate storage for it (a normal `HeapTable.create()`), then
insert one row describing it into the catalog's own table.

This isn't a shortcut taken to save effort — it's genuinely how production databases do it.
Postgres's catalog tables are, themselves, rows that the catalog can describe. There's no
separate "metadata engine"; it's the same storage engine, pointed at itself.

## 4. Two different binary formats, on purpose

Phase 0 and Phase 1 built a binary format where every field lives at a fixed, known offset
(`Page.getInt(4)`, a slot directory entry at `HEADER_SIZE + slotIndex * SLOT_SIZE`) — because
callers need *random access* into a page's contents (jump straight to slot 7 without touching
slots 0–6).

`TableInfo.serialize()` uses a different shape: a sequential, self-describing stream —
`DataOutputStream.writeUTF(name)`, `writeInt(firstPageId)`, `writeInt(columnCount)`, then each
column's name and type in turn — decoded the same way, front to back, via `DataInputStream`.
This is the right tool here because a `TableInfo` is never partially read; it's always
deserialized as one complete unit right after `HeapTable.scanAll()` hands back its bytes. There's
no need to jump into the middle of it, so paying for fixed offsets (and the bookkeeping to
compute them for variable-length strings) buys us nothing. This is still a "custom binary
format" in the sense the original project plan called for — we've fully defined the exact byte
layout ourselves — it's just a sequential one rather than an offset-addressed one, matched to how
the data is actually used.

## 5. What `Catalog` actually exposes

- `Catalog.bootstrap(diskManager)` — initializes a brand-new, empty database file. Call exactly
  once, ever, per file.
- `Catalog.open(diskManager)` — reopens an existing file's catalog, loading every table's
  `TableInfo` into an in-memory cache (a `Map<String, TableInfo>`) so lookups don't need to
  rescan the catalog table on every call.
- `createTable(name, columns)` — allocates a new `HeapTable` for the table's row data, then
  records its schema as a new row in the catalog table.
- `getTable(name)` / `tableExists(name)` / `listTableNames()` — schema introspection, served
  from the in-memory cache.
- `openTable(name)` — the bridge back down to the storage engine: looks up a table's first page
  id and returns a ready-to-use `HeapTable` for actually inserting/reading/deleting rows.

## 6. What Phase 2 deliberately does not do

- **No `DROP TABLE` or `ALTER TABLE` yet.** Only creation is supported; removing or changing a
  table's schema after the fact is future work (it's also not needed until the SQL layer exists
  to ask for it).
- **No index metadata yet**, even though "remember indexes" was in the original phase
  description — there's nothing to remember until Phase 6 builds an actual index structure.
  `TableInfo` can grow an `indexes` field then without disturbing anything built on top of it now.
- **No column constraints** (`NOT NULL`, `PRIMARY KEY`, uniqueness, ...) — `ColumnDefinition` is
  just a name and a type for now.
- **In-memory cache, not fully synchronized with disk on every read.** The cache is loaded once
  at `open()` time and updated in memory on every `createTable()` — correct for a single
  `Catalog` instance, but multiple processes (or the eventual network server handling concurrent
  clients) sharing one file isn't handled yet; that's downstream of the Lock Manager (Phase 8).

## 7. What to look at in the code

- `src/main/java/com/strata/catalog/DataType.java` — the (small, growable) set of column types.
- `src/main/java/com/strata/catalog/ColumnDefinition.java` — one column's name + type.
- `src/main/java/com/strata/catalog/TableInfo.java` — a table's full schema, plus the
  `serialize()`/`deserialize()` binary format described above.
- `src/main/java/com/strata/catalog/Catalog.java` — bootstrapping, the well-known root page,
  and the create/lookup/open API.
- `src/test/java/com/strata/catalog/TableInfoTest.java` and `CatalogTest.java` — 13 tests
  covering the serialization round trip, bootstrap/open guard rails, duplicate/empty-schema
  rejection, `openTable` actually working end-to-end, and catalog + data both surviving a
  simulated restart.
