package com.strata.transaction;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.catalog.Catalog;
import com.strata.executor.ExecutionResult;
import com.strata.lock.LockManager;
import com.strata.recovery.WriteAheadLog;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SessionBufferPoolTest {

    /**
     * Durability under NO-FORCE: COMMIT returns once the log record is durable; the data file
     * may not have the change yet. "Crash" (close every handle without flushing) and reopen
     * through recovery: the committed row must be there.
     */
    public void testAutocommitStatementIsDurableWithoutAnyFlush() throws IOException {
        assertSurvivesCrash(session -> {
            session.execute("CREATE TABLE users (id INTEGER);");
            session.execute("INSERT INTO users VALUES (1);");
        }, 1);
    }

    public void testExplicitTransactionCommitIsDurableWithoutAnyFlush() throws IOException {
        assertSurvivesCrash(session -> {
            session.execute("CREATE TABLE users (id INTEGER);");
            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");
            session.execute("INSERT INTO users VALUES (2);");
            session.execute("COMMIT;");
        }, 2);
    }

    public void testCommitDoesNotForceDataPagesToDisk() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            WriteAheadLog wal = Session.prepareWal(dm);
            Session session = Session.bootstrap(dm, new BufferPool(dm, 64), new LockManager(), wal);
            session.execute("CREATE TABLE users (id INTEGER);");
            long writesBefore = dm.pageWriteCount();
            session.execute("INSERT INTO users VALUES (1);");
            assertEquals(writesBefore, dm.pageWriteCount(), "NO-FORCE: a commit writes only the log, not data pages");
            wal.close();
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    @FunctionalInterface
    private interface Workload {
        void run(Session session) throws IOException;
    }

    private static void assertSurvivesCrash(Workload workload, int expectedRows) throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            DiskManager dm = new DiskManager(tmp);
            WriteAheadLog wal = Session.prepareWal(dm);
            Session session = Session.bootstrap(dm, new BufferPool(dm, 64), new LockManager(), wal);
            workload.run(session);
            dm.close();  // crash: the buffer pool's dirty pages are simply lost
            wal.close();

            try (DiskManager dm2 = new DiskManager(tmp);
                 Session reopened = Session.open(dm2)) { // runs recovery
                ExecutionResult.Rows rows = (ExecutionResult.Rows) reopened.execute("SELECT * FROM users;");
                assertEquals(expectedRows, rows.rows().size(), "committed rows must be recovered from the log");
            }
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    public void testCommittedDataSurvivesFullCloseAndReopen() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (DiskManager dm1 = new DiskManager(tmp);
                 Session session = Session.bootstrap(dm1)) {
                session.execute("CREATE TABLE users (id INTEGER, name VARCHAR);");
                session.execute("INSERT INTO users VALUES (1, 'Alice');");
            } // Session.close() flushes and closes dm1 and wal

            try (DiskManager dm2 = new DiskManager(tmp);
                 Session reopened = Session.open(dm2)) {
                ExecutionResult.Rows result = (ExecutionResult.Rows) reopened.execute("SELECT * FROM users;");
                assertEquals(1, result.rows().size());
                assertEquals("Alice", result.rows().get(0).values().get(1));
            }
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp.toString() + ".wal"));
        }
    }

    public void testRolledBackChangesAreNeverFlushed() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            BufferPool bufferPool = new BufferPool(dm, 64);
            WriteAheadLog wal = Session.prepareWal(dm);
            Session session = Session.bootstrap(dm, bufferPool, new LockManager(), wal);
            session.execute("CREATE TABLE users (id INTEGER);");

            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");
            session.execute("ROLLBACK;");
            bufferPool.flushAll(); // write out everything the buffer pool holds

            Catalog directView = Catalog.open(dm);
            assertTrue(directView.openTable("users").scanAll().isEmpty(),
                "a rolled-back transaction's writes must never reach the buffer pool, let alone disk");
            wal.close();
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    public void testMultipleSessionsSharingABufferPoolSeeEachOthersCommits() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            BufferPool bufferPool = new BufferPool(dm, 64);
            LockManager lockManager = new LockManager();
            WriteAheadLog wal = Session.prepareWal(dm);

            Session session1 = Session.bootstrap(dm, bufferPool, lockManager, wal);
            session1.execute("CREATE TABLE t (id INTEGER);");
            session1.execute("INSERT INTO t VALUES (1);");

            Session session2 = Session.open(dm, bufferPool, lockManager, wal);
            ExecutionResult.Rows result = (ExecutionResult.Rows) session2.execute("SELECT * FROM t;");
            assertEquals(1, result.rows().size(),
                "a second session sharing the same buffer pool must see the first session's committed insert");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
