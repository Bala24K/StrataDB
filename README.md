# StrataDB

[![CI](https://github.com/Bala24K/StrataDB/actions/workflows/ci.yml/badge.svg)](https://github.com/Bala24K/StrataDB/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Tests](https://img.shields.io/badge/tests-296%20passing-brightgreen)

A relational database engine written from scratch in Java 21. It uses no database engine, ORM or SQL parser library.

StrataDB stores tables in 4 KiB slotted pages and caches them in an LRU buffer pool. It indexes them with B+Trees and hash indexes, parses SQL with a hand-written recursive-descent parser, and runs transactions under strict two-phase locking with deadlock detection. Commits are made durable by a group-commit write-ahead log, and the engine recovers after crashes. Many clients can share one database over TCP.

```text
stratadb> CREATE TABLE users (id INTEGER, name VARCHAR, age INTEGER);
Table created: users
stratadb> CREATE INDEX idx_age ON users (age);
Index created: idx_age
stratadb> INSERT INTO users VALUES (1, 'Alice', 30);
Inserted 1 row
stratadb> SELECT name, age FROM users WHERE age >= 26 ORDER BY age DESC;
name	age
------------------------
Carol	41
Alice	30
(2 rows)
```

## Highlights

- **Storage engine.** Slotted pages with in-place updates and compaction, heap files with an O(1) insert tail hint, and an LRU buffer pool with lazy write-back.
- **Indexes.** A B+Tree over `(key, RecordId)` entries supports point and range scans; it is the default for numeric columns. A static hash index is also available (`USING HASH`).
- **Query processing.** A hand-written lexer and parser produce a sealed-record AST. A rule-based planner chooses between a full scan, an index lookup and a B+Tree range scan. Execution is a push pipeline that decodes rows straight from the page buffer.
- **Transactions.**
  - Each transaction writes to a private page overlay.
  - Statement-level savepoints undo a failed statement without aborting the transaction.
  - Isolation is strict 2PL on a catalog lock plus table S/X locks, which makes it serializable at table granularity.
  - Lock queues are FIFO, and deadlocks are detected on a waits-for graph.
- **Durability.**
  - The write-ahead log follows a no-steal, no-force, redo-only policy and stores full page images with CRC32C checksums.
  - Group commit lets concurrent commits share one fsync.
  - Checkpoints run automatically.
  - Recovery tells a torn final record apart from corruption of an acknowledged commit, and refuses to start rather than silently drop committed data.
- **Server.** A thread-per-connection TCP server with a text protocol that frames responses by row count, plus an interactive shell and a client.

## Measured results

The 2026-11 engineering upgrade audited the engine, fixed six reproduced correctness bugs (the worst let concurrent transactions corrupt each other's pages), then improved performance one measured step at a time. Final numbers come from interleaved before/after runs on the same machine:

| Workload | Before | After |
|---|---:|---:|
| fsyncs per commit | 2.03 (insert), 4.99 (indexed update) | **1.00**; 0.26 with 8 concurrent writers |
| 100-row range query | 148–169 /s (full scan) | 12,868–14,646 /s (**~87×**, B+Tree range scan) |
| Bulk insert | 7,419–10,456 rows/s | 122,084–158,649 rows/s (**~15×**) |
| Indexed point lookup | 9,243–9,272 /s | 62,561–85,372 /s (**~7–9×**) |
| Indexed update | 225–286 /s | 1,040–1,335 /s (**~4–6×**) |
| 8 writers, separate tables | 691–797 /s; 1 → 8 threads scaled only 1.3× | 3,156–3,228 /s (**~4–5×**) |
| 8 writers, one table: p99 / max latency | 179–235 ms / 1.45–1.83 s | ~12 ms / ~13 ms (median rose from ~3 to ~7.6 ms) |
| Tests | 236, none run by `mvn test` | **296**, run by Maven and CI on Linux and Windows |

The numbers come from a single laptop (16 logical CPUs, NVMe SSD, HotSpot 23), so read them as relative improvements. Methodology, raw output and the measured noise floor are in [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md). Every claim is checked against raw output in [`docs/FINAL_ENGINEERING_REPORT.md`](docs/FINAL_ENGINEERING_REPORT.md).

## Quick start

You need **JDK 21+**. Maven is optional.

```bash
# Build and run the full test suite (unit, regression, randomized, crash-injection, kill -9)
mvn -B verify

# Or without Maven
java scripts/Build.java .                     # compiles to out/classes and out/test-classes
./scripts/run-tests.sh                        # Windows: powershell -ExecutionPolicy Bypass -File scripts/run-tests.ps1
```

Run the database:

```bash
java -cp out/classes com.strata.Main shell demo.db           # embedded interactive SQL shell ('quit' to exit)
java -cp out/classes com.strata.Main server demo.db 5432     # multi-client TCP server
java -cp out/classes com.strata.Main client localhost 5432   # interactive network client (or: nc localhost 5432)
```

Run the benchmarks:

```bash
scripts/bench.sh workload --scale medium --runs 3 --label my-run   # end-to-end SQL workloads
scripts/bench.sh jmh -prof gc                                     # JMH microbenchmarks
```

On Windows, run the same benchmark arguments with `powershell -File scripts/bench.ps1`.

## Supported SQL

- `CREATE TABLE` with `INTEGER`, `BIGINT`, `VARCHAR` and `BOOLEAN` columns.
- `CREATE INDEX name ON table (column) [USING BTREE | HASH]`.
- `INSERT`, `UPDATE … SET col = literal`, `DELETE`.
- `SELECT` with `WHERE` (`= <> != < > <= >=`, `AND`, `OR`, parentheses, three-valued NULL logic), `ORDER BY` and `LIMIT`.
- `BEGIN`, `COMMIT`, `ROLLBACK`.

Not supported: joins, aggregates, subqueries, `NOT`, `IS NULL`, `DROP`/`ALTER`, and constraints.

## Guarantees and limits

| Property | Status |
|---|---|
| Atomic transactions, including across crashes | Yes |
| Atomic statements inside a transaction | Yes (savepoints) |
| Durability once `COMMIT` returns | Yes against process crashes (tested with injected write failures and `kill -9`); power loss not tested |
| Isolation | Serializable at table granularity (no MVCC, no row locks) |
| Deadlocks | Detected immediately; the victim is rolled back |
| Authentication / access control | Not implemented |

Known limitations, in priority order, are listed in [`docs/AUDIT.md`](docs/AUDIT.md#not-done-known-limitations-in-rough-priority-order). The main ones:
- Full-page WAL images cost about 70× write amplification.
- Writers to one hot table serialize.
- There is no free-page list.

## Documentation

| Document | Contents |
|---|---|
| [`docs/ENGINE.md`](docs/ENGINE.md) | How the engine works: storage format, indexes, execution, concurrency, WAL and recovery, guarantees |
| [`docs/FINAL_ENGINEERING_REPORT.md`](docs/FINAL_ENGINEERING_REPORT.md) | Feature inventory, the six bugs and their fixes, every benchmark result, claims verified against evidence |
| [`docs/AUDIT.md`](docs/AUDIT.md) | Audit findings, decisions taken, known limitations |
| [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) | Methodology and results for each upgrade step |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/ROADMAP.md`](docs/ROADMAP.md) | Module map and phase-by-phase build history |
| `docs/phaseN-*.md` | Concept write-ups for each build phase (storage → networking) |

## Project layout

```text
StrataDB/
  pom.xml                       Maven build; the `bench` profile adds JMH
  .github/workflows/ci.yml      mvn -B verify on Ubuntu and Windows, JDK 21
  docs/                         Engine internals, report, audit, benchmarks, architecture, phase notes
  scripts/                      Dependency-free build/test scripts and benchmark runners
  src/main/java/com/strata/     Engine, one package per layer (storage, buffer, catalog, lexer, parser,
                                optimizer, executor, index, transaction, lock, recovery, engine, network)
  src/test/java/com/strata/     Tests, mirroring the main source tree
  src/test-junit/java/          JUnit 5 bridge so `mvn test` runs the whole suite
  src/bench/java/               JMH microbenchmarks and the end-to-end workload harness
  bench-results/                Raw output of every recorded benchmark run
```

## Why "Strata"

A DBMS is a stack of layers, each built entirely on the guarantees of the layer below, the way rock strata are laid down one on top of another:

```text
   SQL / query layer      ← what the user sees
   execution & planning
   transactions & locks
   indexes
   buffer pool
   storage engine
   ───────────────────
   raw pages on disk      ← bedrock
```

You can't understand or trust a layer until the one below it is solid. That ordering, bedrock first, is how the project was built.

## Ground rules

- No existing database engines, ORMs or SQL parser libraries: every subsystem is built from first principles.
- Every subsystem is its own module with a small, deliberate interface and its own tests.
- A phase isn't done until it works and is tested, and every performance claim is backed by a recorded measurement.
