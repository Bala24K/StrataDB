package com.strata.transaction;

import com.strata.buffer.BufferPool;
import com.strata.catalog.Catalog;
import com.strata.executor.ExecutionResult;
import com.strata.executor.Executor;
import com.strata.lock.LockManager;
import com.strata.parser.Parser;
import com.strata.parser.ast.Statement;
import com.strata.recovery.WriteAheadLog;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The thing a client actually talks to: parses SQL text and runs it, deciding along the way
 * whether each statement belongs to an explicit transaction or should autocommit on its own.
 * This is the layer that gives {@code BEGIN}/{@code COMMIT}/{@code ROLLBACK} their meaning —
 * {@link Executor} itself deliberately refuses to run those three statement kinds (see its
 * source), because transaction *lifecycle* is a session-level concern, not an execution-level
 * one: {@code Executor} only ever knows how to run statements against one fixed {@code Catalog}
 * snapshot; deciding *which* {@code Catalog} (which transaction) a given statement should run
 * against is exactly what this class exists to do.
 *
 * <h2>Autocommit by default</h2>
 * A statement issued with no surrounding {@code BEGIN}/{@code COMMIT} still gets the same
 * atomicity guarantee every other statement always had: {@link #execute(String)} wraps it in
 * its own brand-new {@link Transaction}, runs it, and commits immediately — mirroring how most
 * real databases default to "autocommit" mode outside an explicit transaction block.
 *
 * <h2>A fresh {@code Catalog} per transaction (a known inefficiency)</h2>
 * Every autocommit statement, and every {@code BEGIN}, opens a brand-new {@code Catalog}
 * against a fresh {@link Transaction} — which means re-scanning the entire catalog table from
 * scratch every single time. That's correct (it always reflects the latest committed state,
 * exactly what a real "start of a transaction" snapshot should show), but it's wasteful at any
 * real scale. Not worth building a smarter cache until it's actually slow.
 *
 * <h2>Nested transactions aren't supported</h2>
 * Issuing {@code BEGIN} while already inside a transaction throws — there's no
 * {@code SAVEPOINT} mechanism (yet) to make nesting meaningful.
 *
 * <h2>Locking (Phase 8): strict two-phase locking, at table granularity</h2>
 * Before running any statement that touches a table, a {@code Session} acquires the
 * appropriate {@link LockManager} lock on that table first — shared for {@code SELECT},
 * exclusive for {@code CREATE TABLE}/{@code INSERT}/{@code UPDATE}/{@code DELETE} — and holds
 * it until the owning transaction ends. Multiple {@code Session}s constructed against the
 * <em>same</em> {@link LockManager} correctly block each other's conflicting statements.
 *
 * <h2>Buffer Pool (Phase 9): every {@code Transaction} sits on a shared cache, not raw disk</h2>
 * {@link Transaction} is constructed against a {@link BufferPool} rather than the raw
 * {@link DiskManager} directly, so a transaction's writes only reach an in-memory cache when it
 * commits, not necessarily real disk. {@link #execute} calls {@code bufferPool.flushAll()}
 * immediately after every successful commit to keep a committed transaction durable.
 *
 * <h2>Write-Ahead Log and recovery (Phase 11)</h2>
 * Every {@link Transaction} this class creates is also given the session's shared
 * {@link WriteAheadLog}, so {@code commit()} durably logs its writes *before* applying them
 * (see {@link Transaction} and {@link WriteAheadLog}'s own docs for why that ordering matters).
 * {@link #open(DiskManager, BufferPool, LockManager, WriteAheadLog)} expects the WAL passed to
 * it to have already been prepared via {@link #prepareWal}, which runs {@link WriteAheadLog#recover}
 * — replaying any committed-but-not-fully-applied writes from a prior crash — before anything
 * else is allowed to touch the database file.
 *
 * <p>Just like {@link LockManager} and {@link BufferPool}, multiple {@code Session}s sharing one
 * database file must share <em>one</em> {@link WriteAheadLog} instance too — logging from two
 * independent file handles at once would race on the log's own append position.
 */
public final class Session implements AutoCloseable {

    private static final int DEFAULT_BUFFER_POOL_CAPACITY = 64;

    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final LockManager lockManager;
    private final WriteAheadLog wal;
    private Transaction activeTransaction;
    private Executor activeExecutor;

    private Session(DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.lockManager = lockManager;
        this.wal = wal;
    }

    /** The WAL file path StrataDB uses for a given database file — always alongside it, with a {@code .wal} suffix. */
    public static Path walPathFor(DiskManager diskManager) {
        return Path.of(diskManager.getPath().toString() + ".wal");
    }

    /**
     * Prepares this database's write-ahead log for use: replays and clears any committed work
     * left over from a prior crash, then opens the (now-empty) log ready for new records. Must
     * be called — and its result shared across every {@code Session} on this database — before
     * any of them run.
     */
    public static WriteAheadLog prepareWal(DiskManager diskManager) throws IOException {
        Path walPath = walPathFor(diskManager);
        WriteAheadLog.recover(walPath, diskManager); // must happen before anything else touches diskManager
        return WriteAheadLog.open(walPath);
    }

    /** Initializes a brand-new database file with its own private buffer pool, lock manager, and WAL. */
    public static Session bootstrap(DiskManager diskManager) throws IOException {
        Files.deleteIfExists(walPathFor(diskManager)); // brand-new database: any stale WAL debris is irrelevant
        WriteAheadLog wal = WriteAheadLog.open(walPathFor(diskManager));
        return bootstrap(diskManager, new BufferPool(diskManager, DEFAULT_BUFFER_POOL_CAPACITY), new LockManager(), wal);
    }

    /**
     * Initializes a brand-new database file, sharing {@code bufferPool}, {@code lockManager},
     * and {@code wal} with other sessions — use this (and
     * {@link #open(DiskManager, BufferPool, LockManager, WriteAheadLog)}) to model multiple
     * concurrent "users" against the same database within one process.
     */
    public static Session bootstrap(DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal) throws IOException {
        Catalog.bootstrap(diskManager); // a one-time, directly-against-disk setup step — see Catalog's own docs
        return new Session(diskManager, bufferPool, lockManager, wal);
    }

    /** Opens a session against an existing database file with its own private buffer pool, lock manager, and WAL — running recovery first. */
    public static Session open(DiskManager diskManager) throws IOException {
        WriteAheadLog wal = prepareWal(diskManager);
        return open(diskManager, new BufferPool(diskManager, DEFAULT_BUFFER_POOL_CAPACITY), new LockManager(), wal);
    }

    /** Opens a session against an existing database file, sharing {@code bufferPool}, {@code lockManager}, and {@code wal}. */
    public static Session open(DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal) {
        return new Session(diskManager, bufferPool, lockManager, wal);
    }

    public ExecutionResult execute(String sql) throws IOException {
        return execute(Parser.parse(sql));
    }

    public ExecutionResult execute(Statement statement) throws IOException {
        if (statement instanceof Statement.Begin) {
            beginTransaction();
            return new ExecutionResult.TransactionStarted();
        }
        if (statement instanceof Statement.Commit) {
            requireActiveTransaction();
            activeTransaction.commit();
            bufferPool.flushAll(); // FORCE: make this commit durable before acknowledging it
            lockManager.releaseAll(activeTransaction);
            clearActiveTransaction();
            return new ExecutionResult.TransactionCommitted();
        }
        if (statement instanceof Statement.Rollback) {
            requireActiveTransaction();
            activeTransaction.rollback();
            lockManager.releaseAll(activeTransaction);
            clearActiveTransaction();
            return new ExecutionResult.TransactionRolledBack();
        }

        LockRequest lockRequest = lockRequestFor(statement);

        if (activeTransaction != null) {
            // Inside an explicit transaction: acquire the lock under the *transaction's*
            // identity so it's held until COMMIT/ROLLBACK releases everything at once, not
            // released after just this one statement.
            acquireLock(activeTransaction, lockRequest);
            return activeExecutor.execute(statement);
        }

        // Autocommit: this one statement gets its own transaction, its lock held only for its
        // own duration, applied and made durable immediately.
        Transaction txn = new Transaction(bufferPool, wal);
        acquireLock(txn, lockRequest);
        try {
            Executor executor = new Executor(Catalog.open(txn));
            ExecutionResult result = executor.execute(statement);
            txn.commit();
            bufferPool.flushAll(); // FORCE, same as the explicit-transaction path above
            return result;
        } finally {
            lockManager.releaseAll(txn);
        }
    }

    @Override
    public void close() throws IOException {
        bufferPool.flushAll(); // defensive: every commit already flushes, so this should be a no-op in practice
        diskManager.close();
        wal.close();
    }

    private void beginTransaction() throws IOException {
        if (activeTransaction != null) {
            throw new IllegalStateException(
                "Already inside a transaction — nested BEGIN isn't supported (no SAVEPOINT yet)");
        }
        activeTransaction = new Transaction(bufferPool, wal);
        activeExecutor = new Executor(Catalog.open(activeTransaction));
    }

    private void requireActiveTransaction() {
        if (activeTransaction == null) {
            throw new IllegalStateException("No active transaction — issue BEGIN first");
        }
    }

    private void clearActiveTransaction() {
        activeTransaction = null;
        activeExecutor = null;
    }

    private void acquireLock(Transaction owner, LockRequest request) {
        if (request.mode() == LockManager.LockMode.SHARED) {
            lockManager.acquireShared(owner, request.tableName());
        } else {
            lockManager.acquireExclusive(owner, request.tableName());
        }
    }

    private record LockRequest(String tableName, LockManager.LockMode mode) {}

    /** Every statement Executor actually runs touches exactly one named table — Begin/Commit/Rollback never reach here. */
    private static LockRequest lockRequestFor(Statement statement) {
        return switch (statement) {
            case Statement.CreateTable stmt -> new LockRequest(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.CreateIndex stmt -> new LockRequest(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Insert stmt -> new LockRequest(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Update stmt -> new LockRequest(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Delete stmt -> new LockRequest(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Select stmt -> new LockRequest(stmt.tableName(), LockManager.LockMode.SHARED);
            case Statement.Begin ignored -> throw new IllegalStateException("unreachable: Begin is handled before lockRequestFor is called");
            case Statement.Commit ignored -> throw new IllegalStateException("unreachable: Commit is handled before lockRequestFor is called");
            case Statement.Rollback ignored -> throw new IllegalStateException("unreachable: Rollback is handled before lockRequestFor is called");
        };
    }
}
