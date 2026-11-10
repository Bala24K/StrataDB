package com.strata.transaction;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.executor.ExecutionResult;
import com.strata.lock.LockManager;
import com.strata.lock.LockTimeoutException;
import com.strata.recovery.WriteAheadLog;
import com.strata.storage.page.DiskManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for concurrency bugs found in the 2026-11 audit. Each test reproduces a
 * scenario that corrupted data (or hung) before its fix; see docs/AUDIT.md.
 */
public class ConcurrencyRegressionTest {

    private static final String WIDE = "x".repeat(1000); // ~4 rows per page: forces page growth quickly

    /** Bug 1: two open transactions growing different tables used to be handed the same new page id. */
    public void testConcurrentTransactionsGrowingDifferentTablesDoNotShareNewPages() throws Exception {
        withDb((dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            Session s2 = Session.open(dm, bp, lm, wal);
            s1.execute("CREATE TABLE a (id INTEGER, v VARCHAR);");
            s1.execute("CREATE TABLE b (id INTEGER, v VARCHAR);");

            s1.execute("BEGIN;");
            s2.execute("BEGIN;");
            for (int i = 0; i < 10; i++) s1.execute("INSERT INTO a VALUES (" + i + ", '" + WIDE + "');");
            for (int i = 0; i < 10; i++) s2.execute("INSERT INTO b VALUES (" + (100 + i) + ", '" + WIDE + "');");
            s1.execute("COMMIT;");
            s2.execute("COMMIT;");

            assertEquals(10, count(s1, "SELECT * FROM a WHERE id < 100;"), "table a must keep all of its own rows");
            assertEquals(0, count(s1, "SELECT * FROM a WHERE id >= 100;"), "table b's rows must not leak into table a");
            assertEquals(10, count(s1, "SELECT * FROM b WHERE id >= 100;"));
            assertEquals(0, count(s1, "SELECT * FROM b WHERE id < 100;"));
        });
    }

    /**
     * Bug 1, autocommit form: concurrent single-row inserts into one table used to create a
     * cyclic page chain, after which HeapTable.insert looped forever while holding the table's
     * exclusive lock.
     */
    public void testConcurrentAutocommitInsertsIntoOneTableAreAllKept() throws Exception {
        withDb((dm, bp, lm, wal) -> {
            Session.bootstrap(dm, bp, lm, wal).execute("CREATE TABLE t (id INTEGER, v VARCHAR);");
            int threads = 4;
            int perThread = 30;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * 1000;
                Thread worker = new Thread(() -> {
                    try {
                        Session s = Session.open(dm, bp, lm, wal);
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            s.execute("INSERT INTO t VALUES (" + (base + i) + ", '" + WIDE + "');");
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true); // a hung worker must not keep the test JVM alive
                workers.add(worker);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "inserters must finish (a hang here means a cyclic page chain)");
            if (failure.get() != null) throw new AssertionError("an inserter failed", failure.get());

            assertEquals(threads * perThread, count(Session.open(dm, bp, lm, wal), "SELECT * FROM t;"));
        });
    }

    /**
     * Bug 2: two concurrent CREATE TABLEs both rewrote catalog page 0 and one table vanished.
     * Now DDL takes an exclusive catalog lock, so the second one waits (here: times out) instead
     * of silently corrupting the catalog, and succeeds once the first commits.
     */
    public void testConcurrentCreateTablesDoNotLoseEitherTable() throws Exception {
        withDb(300, (dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            Session s2 = Session.open(dm, bp, lm, wal);
            s1.execute("BEGIN;");
            s2.execute("BEGIN;");
            s1.execute("CREATE TABLE x (id INTEGER);");
            assertThrows(LockTimeoutException.class, () -> s2.execute("CREATE TABLE y (id INTEGER);"),
                "DDL must wait for another transaction's uncommitted DDL");
            s1.execute("COMMIT;");
            s2.execute("CREATE TABLE y (id INTEGER);");
            s2.execute("COMMIT;");

            Session check = Session.open(dm, bp, lm, wal);
            assertEquals(0, count(check, "SELECT * FROM x;"));
            assertEquals(0, count(check, "SELECT * FROM y;"));
        });
    }

    /**
     * Bug 3: an explicit transaction loaded the catalog at BEGIN, so an index created by another
     * session after that BEGIN was invisible to it, and its inserts were never indexed.
     */
    public void testIndexCreatedAfterAnotherSessionsBeginIsStillMaintained() throws Exception {
        withDb(300, (dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            Session s2 = Session.open(dm, bp, lm, wal);
            s1.execute("CREATE TABLE t (id INTEGER, v VARCHAR);");
            s1.execute("INSERT INTO t VALUES (1, 'a');");

            s1.execute("BEGIN;");
            s2.execute("CREATE INDEX idx_id ON t (id);");
            s1.execute("INSERT INTO t VALUES (99, 'b');");
            s1.execute("COMMIT;");

            assertEquals(1, count(s2, "SELECT * FROM t WHERE id = 99;"), "the index must contain the row inserted after BEGIN");
        });
    }

    /** Bug 3, other ordering: once a transaction has read the catalog, DDL must wait for it to end. */
    public void testDdlWaitsForTransactionsThatHaveReadTheCatalog() throws Exception {
        withDb(300, (dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            Session s2 = Session.open(dm, bp, lm, wal);
            s1.execute("CREATE TABLE t (id INTEGER);");

            s1.execute("BEGIN;");
            s1.execute("INSERT INTO t VALUES (7);");
            assertThrows(LockTimeoutException.class, () -> s2.execute("CREATE INDEX idx_id ON t (id);"));
            s1.execute("COMMIT;");
            s2.execute("CREATE INDEX idx_id ON t (id);");
            assertEquals(1, count(s2, "SELECT * FROM t WHERE id = 7;"));
        });
    }

    // ---- helpers ----

    static int count(Session s, String sql) throws Exception {
        return ((ExecutionResult.Rows) s.execute(sql)).rows().size();
    }

    @FunctionalInterface
    interface DbTest {
        void run(DiskManager dm, BufferPool bp, LockManager lm, WriteAheadLog wal) throws Exception;
    }

    static void withDb(DbTest test) throws Exception {
        withDb(10_000, test);
    }

    static void withDb(long lockTimeoutMillis, DbTest test) throws Exception {
        Path tmp = Files.createTempFile("stratadb-regression", ".db");
        Files.deleteIfExists(tmp);
        DiskManager dm = new DiskManager(tmp);
        WriteAheadLog wal = Session.prepareWal(dm);
        try {
            test.run(dm, new BufferPool(dm, 64), new LockManager(lockTimeoutMillis), wal);
        } finally {
            dm.close();
            wal.close();
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }
}
