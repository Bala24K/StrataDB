# StrataDB

A production-inspired relational database engine, built from first principles in Java 21 —
no existing database engines, ORMs, or SQL parser libraries used anywhere.

## Why "Strata"

A DBMS is a stack of layers, each one built entirely on the guarantees of the layer below it,
the way rock strata are laid down one on top of another:

```
   SQL / query layer      ← what the user sees
   execution & planning
   transactions & locks
   indexes
   buffer pool
   storage engine
   ───────────────────
   raw pages on disk      ← bedrock
```

You genuinely cannot understand or trust the layer above until the layer below is solid.
That ordering — bedrock first, always — is the whole philosophy of how this project is built,
which is what the name is getting at.

## Status

Phases 0–12 are complete: storage engine, catalog, SQL lexer/parser, executor, hash and B+Tree
indexes, transactions, locking, buffer pool, rule-based planner, write-ahead log with crash
recovery, and a multi-client TCP server. Authentication (Phase 13) is **not** implemented.

A **2026-11 engineering upgrade** audited the whole engine, fixed six reproduced correctness
bugs (including concurrent transactions corrupting each other's pages), then improved
performance step by step, measuring every change:

| What | Before | After |
|---|---|---|
| Fsyncs per commit | 2–5 (WAL + each dirty page) | 1, shared across concurrent committers (group commit) |
| Concurrent writers, 8 threads, separate tables | flat (no scaling) | scales with threads |
| Indexed lookup at 100k rows (JMH) | 388 µs (static hash) | 2.8 µs (B+Tree) |
| 100-key range query | full table scan | B+Tree range scan |
| Lock contention tail (8 writers, one table) | p99 ~180–235 ms, max ~1.5–1.8 s | p99 ~12 ms, max ~13 ms |
| Tests | 236 (none run by `mvn test`) | 296, run by Maven and CI, incl. crash and kill tests |

See the interleaved before/after comparison in [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md#final-validation).

**Documentation**

- [`docs/ENGINE.md`](docs/ENGINE.md): how the engine works now (storage format, indexes,
  execution, concurrency model, WAL and recovery, guarantees).
- [`docs/AUDIT.md`](docs/AUDIT.md): the audit findings, what was fixed, the decisions made,
  and known limitations.
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md): methodology, results for every step, noise floor.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/ROADMAP.md`](docs/ROADMAP.md): module
  map and phase-by-phase build history; `docs/phaseN-*.md` explain each phase's concepts.

## Project layout

```
StrataDB/
  pom.xml                       Maven build; `bench` profile adds JMH
  docs/                         Engine internals, audit, benchmarks, architecture, roadmap, phase notes
  scripts/                      Build/test scripts (no Maven needed) and benchmark runners
  src/main/java/com/strata/     Production code, organized by module
  src/test/java/com/strata/     Tests, mirroring the main source tree
  src/test-junit/java/          JUnit 5 bridge so `mvn test` runs the whole suite
  src/bench/java/               JMH microbenchmarks and the end-to-end workload harness
  bench-results/                Raw output of every recorded benchmark run
```

### Running the Test Suite

```bash
mvn test
```

This runs every test class (unit, regression, randomized model, crash-injection and
process-kill tests) through a JUnit 5 bridge. The same classes also run without Maven:

**On Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-tests.ps1
```

Or manually:
```powershell
java scripts/Build.java .
java -cp "out/classes;out/test-classes" com.strata.testutil.TestRunner
```

**On Linux / macOS:**
```bash
./scripts/run-tests.sh
```

### Running the Benchmarks

```bash
scripts/bench.sh workload --scale medium --runs 3 --label my-run   # end-to-end SQL workloads
scripts/bench.sh jmh -prof gc                                     # JMH microbenchmarks
```

(`powershell -File scripts/bench.ps1 ...` on Windows.) Methodology and every recorded result
are in [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

## Running the Database

StrataDB provides an interactive CLI in `com.strata.Main`:

### 1. Embedded Interactive SQL Shell
Open an interactive SQL REPL directly against a local database file:
```powershell
java -cp "out/classes" com.strata.Main shell demo.db
```

### 2. Multi-Client TCP Server
Start the StrataDB network server (defaults to port 5432):
```powershell
java -cp "out/classes" com.strata.Main server demo.db 5432
```

### 3. Network SQL Client
Connect an interactive SQL client to a running StrataDB server:
```powershell
java -cp "out/classes" com.strata.Main client localhost 5432
```
*(Or connect with any raw TCP client, e.g. `nc localhost 5432`)*


## Ground rules for this project

- No existing database engines, ORMs, or SQL parser libraries — every subsystem is built
  from first principles.
- Every subsystem (storage engine, page manager, lexer, parser, planner, executor,
  transactions, buffer pool, indexes, recovery, networking) is its own module with a small,
  deliberate public interface and its own unit tests.
- A phase isn't "done" until it's fully working and tested — we don't start Phase N+1 with
  Phase N half-finished.
