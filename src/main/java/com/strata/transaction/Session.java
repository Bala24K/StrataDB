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
 * <h2>Catalog locking</h2>
 * The catalog is shared state: every table's and index's metadata lives in the same heap pages
 * rooted at page 0. Locking only table names left it outside the protocol, which caused two
 * bugs: two concurrent {@code CREATE TABLE}s rewrote the same catalog page and one table was
 * lost; and an explicit transaction that loaded the catalog at {@code BEGIN} could miss an
 * index created afterwards, so its inserts never updated that index. Now every statement first
 * takes a lock on {@link #CATALOG_RESOURCE}: <b>exclusive</b> for DDL, <b>shared</b> for
 * everything else, held to the end of the transaction like every other lock. A transaction
 * loads the catalog only after its first catalog lock is held; since no DDL can commit while
 * that shared lock is held, the loaded copy can't go stale for the rest of the transaction.
 *
 * <p>The cost: an open transaction blocks DDL, and in-progress DDL blocks every other
 * statement. That's catalog-wide rather than per-table because all catalog entries share
 * physical pages. DDL is rare, so this coarse lock is the right trade for now.
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
    private final boolean ownsResources;
    private Transaction activeTransaction;
    private Executor activeExecutor;

    private Session(DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal,
                    boolean ownsResources) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.lockManager = lockManager;
        this.wal = wal;
        this.ownsResources = ownsResources;
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
        Catalog.bootstrap(diskManager);
        return new Session(diskManager, new BufferPool(diskManager, DEFAULT_BUFFER_POOL_CAPACITY), new LockManager(), wal, true);
    }

    /**
     * Initializes a brand-new database file, sharing {@code bufferPool}, {@code lockManager},
     * and {@code wal} with other sessions — use this (and
     * {@link #open(DiskManager, BufferPool, LockManager, WriteAheadLog)}) to model multiple
     * concurrent "users" against the same database within one process.
     */
    public static Session bootstrap(DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal) throws IOException {
        Catalog.bootstrap(diskManager); // a one-time, directly-against-disk setup step — see Catalog's own docs
        return new Session(diskManager, bufferPool, lockManager, wal, false);
    }

    /** Opens a session against an existing database file with its own private buffer pool, lock manager, and WAL — running recovery first. */
    public static Session open(DiskManager diskManager) throws IOException {
        WriteAheadLog wal = prepareWal(diskManager);
        return new Session(diskManager, new BufferPool(diskManager, DEFAULT_BUFFER_POOL_CAPACITY), new LockManager(), wal, true);
    }

    /** Opens a session against an existing database file, sharing {@code bufferPool}, {@code lockManager}, and {@code wal}. */
    public static Session open(DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal) {
        return new Session(diskManager, bufferPool, lockManager, wal, false);
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
            Transaction txn = activeTransaction;
            try {
                txn.commit();
                bufferPool.flushAll(); // FORCE: make this commit durable before acknowledging it
            } finally {
                lockManager.releaseAll(txn);
                clearActiveTransaction();
            }
            return new ExecutionResult.TransactionCommitted();
        }
        if (statement instanceof Statement.Rollback) {
            requireActiveTransaction();
            rollbackActiveTransaction();
            return new ExecutionResult.TransactionRolledBack();
        }

        LockRequest lockRequest = lockRequestFor(statement);

        if (activeTransaction != null) {
            return executeInExplicitTransaction(statement, lockRequest);
        }

        // Autocommit: this one statement gets its own transaction, its locks held only for its
        // own duration, applied and made durable immediately — or, on any failure, discarded.
        Transaction txn = new Transaction(bufferPool, wal);
        boolean committed = false;
        try {
            acquireLocks(txn, lockRequest);
            Executor executor = new Executor(Catalog.open(txn));
            ExecutionResult result = executor.execute(statement);
            txn.commit();
            committed = true;
            bufferPool.flushAll(); // FORCE, same as the explicit-transaction path above
            return result;
        } finally {
            if (!committed) {
                txn.rollback();
            }
            lockManager.releaseAll(txn);
        }
    }

    /**
     * Runs one statement inside the open explicit transaction with <b>statement-level
     * atomicity</b>: if it fails for any reason (bad value, lock timeout, I/O error), every page
     * change it made is undone and the transaction stays open with exactly the state it had
     * before the statement, so the client can retry, continue, or ROLLBACK. (This is the
     * MySQL/Oracle behaviour; PostgreSQL instead aborts the whole transaction.) Locks the failed
     * statement acquired stay held, as strict 2PL requires.
     */
    private ExecutionResult executeInExplicitTransaction(Statement statement, LockRequest lockRequest) throws IOException {
        Transaction txn = activeTransaction;
        txn.beginStatement();
        boolean succeeded = false;
        try {
            // Locks are taken under the transaction's identity, so they're held until
            // COMMIT/ROLLBACK, not released after just this one statement.
            acquireLocks(txn, lockRequest);
            if (activeExecutor == null) {
                // First statement of this transaction: only now, with the catalog lock held,
                // is it safe to load the catalog (see the class docs on catalog locking).
                activeExecutor = new Executor(Catalog.open(txn));
            }
            ExecutionResult result = activeExecutor.execute(statement);
            succeeded = true;
            return result;
        } finally {
            if (succeeded) {
                txn.commitStatement();
            } else {
                txn.rollbackStatement();
                // The executor's in-memory catalog may have recorded a table or index whose
                // pages were just rolled back; reload it from the overlay on the next statement.
                activeExecutor = null;
            }
        }
    }

    /**
     * Ends this session: rolls back any open transaction and releases its locks (essential when a
     * network client disconnects mid-transaction — otherwise its locks would be held forever).
     * The database resources (disk file, buffer pool, WAL) are flushed and closed only if this
     * session created them itself; sessions sharing them with others leave them open.
     */
    @Override
    public void close() throws IOException {
        if (activeTransaction != null) {
            rollbackActiveTransaction();
        }
        if (ownsResources) {
            bufferPool.flushAll(); // defensive: every commit already flushes, so this should be a no-op in practice
            diskManager.close();
            wal.close();
        }
    }

    /** True while an explicit transaction ({@code BEGIN} ... ) is open. */
    public boolean inTransaction() {
        return activeTransaction != null;
    }

    private void rollbackActiveTransaction() {
        Transaction txn = activeTransaction;
        try {
            txn.rollback();
        } finally {
            lockManager.releaseAll(txn);
            clearActiveTransaction();
        }
    }

    private void beginTransaction() throws IOException {
        if (activeTransaction != null) {
            throw new IllegalStateException(
                "Already inside a transaction — nested BEGIN isn't supported (no SAVEPOINT yet)");
        }
        activeTransaction = new Transaction(bufferPool, wal);
        activeExecutor = null; // created lazily, after the first statement's catalog lock is held
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

    /**
     * The lock resource standing for the system catalog. A record type of its own, so it can
     * never be equal to any table-name {@code String}, whatever a user names their table.
     */
    private record CatalogResource() {}

    static final Object CATALOG_RESOURCE = new CatalogResource();

    /** Catalog lock first, then the table lock: one global acquisition order for every statement. */
    private void acquireLocks(Transaction owner, LockRequest request) {
        acquire(owner, CATALOG_RESOURCE, request.catalogMode());
        acquire(owner, request.tableName(), request.tableMode());
    }

    private void acquire(Transaction owner, Object resource, LockManager.LockMode mode) {
        if (mode == LockManager.LockMode.SHARED) {
            lockManager.acquireShared(owner, resource);
        } else {
            lockManager.acquireExclusive(owner, resource);
        }
    }

    private record LockRequest(String tableName, LockManager.LockMode tableMode, LockManager.LockMode catalogMode) {
        static LockRequest dml(String table, LockManager.LockMode tableMode) {
            return new LockRequest(table, tableMode, LockManager.LockMode.SHARED);
        }

        static LockRequest ddl(String table) {
            return new LockRequest(table, LockManager.LockMode.EXCLUSIVE, LockManager.LockMode.EXCLUSIVE);
        }
    }

    /** Every statement Executor actually runs touches exactly one named table — Begin/Commit/Rollback never reach here. */
    private static LockRequest lockRequestFor(Statement statement) {
        return switch (statement) {
            case Statement.CreateTable stmt -> LockRequest.ddl(stmt.tableName());
            case Statement.CreateIndex stmt -> LockRequest.ddl(stmt.tableName());
            case Statement.Insert stmt -> LockRequest.dml(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Update stmt -> LockRequest.dml(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Delete stmt -> LockRequest.dml(stmt.tableName(), LockManager.LockMode.EXCLUSIVE);
            case Statement.Select stmt -> LockRequest.dml(stmt.tableName(), LockManager.LockMode.SHARED);
            case Statement.Begin ignored -> throw new IllegalStateException("unreachable: Begin is handled before lockRequestFor is called");
            case Statement.Commit ignored -> throw new IllegalStateException("unreachable: Commit is handled before lockRequestFor is called");
            case Statement.Rollback ignored -> throw new IllegalStateException("unreachable: Rollback is handled before lockRequestFor is called");
        };
    }
}
