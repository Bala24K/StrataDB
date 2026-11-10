package com.strata.engine;

import com.strata.buffer.BufferPool;
import com.strata.catalog.Catalog;
import com.strata.lock.LockManager;
import com.strata.metrics.Metrics;
import com.strata.recovery.WriteAheadLog;
import com.strata.storage.page.DiskManager;
import com.strata.transaction.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One open database: the file, and the components every session on it must share — exactly one
 * {@link BufferPool} (cache coherence), one {@link LockManager} (mutual exclusion), and one
 * {@link WriteAheadLog} (a single append position and commit order).
 *
 * <p>Before this class existed, the embedded shell, the TCP server and the tests each wired those
 * pieces together by hand, and the copies had drifted: the server reused a stale {@code .wal}
 * file left beside a brand-new database (later replaying it into the wrong database), while
 * {@code Session.bootstrap} correctly deleted it. Opening a database now happens in one place:
 * <ul>
 *   <li><b>New file</b> (missing or empty): delete any stale WAL, bootstrap the catalog.</li>
 *   <li><b>Existing file</b>: run WAL recovery before anything else reads the file.</li>
 * </ul>
 */
public final class Database implements AutoCloseable {

    /**
     * Tunables.
     *
     * @param bufferPoolPages      buffer pool capacity in 4 KiB pages
     * @param lockTimeoutMillis    how long a lock request waits before {@code LockTimeoutException}
     * @param checkpointWalBytes   checkpoint automatically once the log exceeds this size; bounds
     *                             log growth and recovery time
     */
    public record Options(int bufferPoolPages, long lockTimeoutMillis, long checkpointWalBytes) {
        public Options {
            if (bufferPoolPages < 1) throw new IllegalArgumentException("bufferPoolPages must be >= 1");
            if (lockTimeoutMillis < 0) throw new IllegalArgumentException("lockTimeoutMillis must be >= 0");
            if (checkpointWalBytes < 1) throw new IllegalArgumentException("checkpointWalBytes must be >= 1");
        }

        public static Options defaults() {
            return new Options(64, 5_000, 16L * 1024 * 1024);
        }

        public Options withBufferPoolPages(int pages) {
            return new Options(pages, lockTimeoutMillis, checkpointWalBytes);
        }

        public Options withLockTimeoutMillis(long millis) {
            return new Options(bufferPoolPages, millis, checkpointWalBytes);
        }

        public Options withCheckpointWalBytes(long bytes) {
            return new Options(bufferPoolPages, lockTimeoutMillis, bytes);
        }
    }

    private final Path path;
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final LockManager lockManager;
    private final WriteAheadLog wal;
    private final boolean created;
    private boolean closed;

    private Database(Path path, DiskManager diskManager, BufferPool bufferPool, LockManager lockManager,
                     WriteAheadLog wal, boolean created) {
        this.path = path;
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.lockManager = lockManager;
        this.wal = wal;
        this.created = created;
    }

    public static Database open(Path file) throws IOException {
        return open(file, Options.defaults());
    }

    public static Database open(Path file, Options options) throws IOException {
        DiskManager diskManager = new DiskManager(file);
        // Decided after DiskManager has dropped any partial trailing page: a file that holds no
        // complete page was never successfully bootstrapped (bootstrap syncs before returning).
        boolean isNew = diskManager.getNumPages() == 0;
        try {
            WriteAheadLog wal;
            if (isNew) {
                Files.deleteIfExists(Session.walPathFor(diskManager)); // debris from some earlier database
                wal = WriteAheadLog.open(Session.walPathFor(diskManager));
                Catalog.bootstrap(diskManager);
                diskManager.sync(); // the catalog root is written directly, not through the log
            } else {
                wal = Session.prepareWal(diskManager); // recovery runs before anything else reads the file
            }
            Database db = new Database(file, diskManager,
                new BufferPool(diskManager, options.bufferPoolPages()),
                new LockManager(options.lockTimeoutMillis()),
                wal, isNew);
            wal.configureAutoCheckpoint(options.checkpointWalBytes(), db::flushAndSync);
            return db;
        } catch (IOException | RuntimeException e) {
            diskManager.close();
            throw e;
        }
    }

    /** A new session (one client / connection) sharing this database's components. */
    public Session newSession() {
        requireOpen();
        return Session.open(diskManager, bufferPool, lockManager, wal);
    }

    /** True if {@link #open} created this database file rather than opening an existing one. */
    public boolean wasCreated() {
        return created;
    }

    public Path path() {
        return path;
    }

    public BufferPool bufferPool() {
        return bufferPool;
    }

    public DiskManager diskManager() {
        return diskManager;
    }

    public LockManager lockManager() {
        return lockManager;
    }

    public WriteAheadLog wal() {
        return wal;
    }

    /** A snapshot of every engine counter; subtract two snapshots to measure a workload. */
    public Metrics metrics() {
        return new Metrics(
            diskManager.pageReadCount(),
            diskManager.pageWriteCount(),
            diskManager.fsyncCount(),
            diskManager.pagesAllocatedCount(),
            bufferPool.hitCount(),
            bufferPool.missCount(),
            bufferPool.evictionCount(),
            bufferPool.dirtyWritebackCount(),
            wal.commitCount(),
            wal.bytesLogged(),
            wal.fsyncCount(),
            lockManager.acquisitionCount(),
            lockManager.waitCount(),
            lockManager.waitNanos(),
            lockManager.timeoutCount(),
            wal.checkpointCount());
    }

    /**
     * Writes every dirty page to the data file, fsyncs it, and empties the write-ahead log.
     * Runs automatically when the log passes {@link Options#checkpointWalBytes()} and on close.
     */
    public void checkpoint() throws IOException {
        wal.checkpoint(this::flushAndSync);
    }

    private void flushAndSync() throws IOException {
        bufferPool.flushAll();
        diskManager.sync();
    }

    /** Checkpoints (unless a failed commit left recovery work to do) and closes the file and log. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (wal.isHealthy()) {
                checkpoint(); // clean shutdown: data file durable, log empty, fast next open
            }
        } finally {
            diskManager.close();
            wal.close();
        }
    }

    private synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Database " + path + " is closed");
        }
    }
}
