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

Currently on **Phase 13 — Authentication** (users, roles, permissions — the last piece needed
before "multiple clients" from Phase 12 also means "multiple people who shouldn't all have the
same access"). Phase 12 (a real TCP server; also where the most serious bug in the project so
far — a latent race in Phase 8's `LockManager`, only surfaced by genuine multi-client
contention — was found and fixed) is done, on top of Phase 11 (a Write-Ahead Log), Phase 10 (a
real query planner), Phase 9 (a buffer pool), Phase 8 (concurrent access), Phase 7 (real
transactions), and the full SQL pipeline (Phases 0 through 5). See
[`docs/ROADMAP.md`](docs/ROADMAP.md) for the full phase-by-phase plan and current progress,
and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the modules fit together.

Each phase's concepts are written up in `docs/phaseN-*.md` as we go — read those alongside the
code; they're meant to explain *why* the code looks the way it does, not just what it does.

## Project layout

```
StrataDB/
  pom.xml                    Maven build (for use on a real machine with internet access)
  docs/                       Architecture notes, roadmap, per-phase deep dives
  scripts/                    Sandbox-friendly build/test scripts (see note below)
  src/main/java/com/strata/   Production code, organized by module
  src/test/java/com/strata/   Tests, mirroring the main source tree
```

### Running the Test Suite

The test suite runs using `com.strata.testutil.TestRunner`, running 236 unit and integration tests across all engine layers.

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
