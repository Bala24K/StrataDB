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

    public void testAutocommitStatementIsDurableOnDiskImmediately() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            Session session = Session.bootstrap(dm);
            session.execute("CREATE TABLE users (id INTEGER);");
            session.execute("INSERT INTO users VALUES (1);");

            // read directly against the real DiskManager, bypassing Session/BufferPool entirely —
            // if FORCE-at-commit is working, the data must already be there, with no explicit flush call.
            Catalog directView = Catalog.open(dm);
            assertEquals(1, directView.openTable("users").scanAll().size(),
                "an autocommit statement must be durable on disk the instant it returns, even through the buffer pool");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void testExplicitTransactionCommitIsDurableOnDiskImmediately() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            Session session = Session.bootstrap(dm);
            session.execute("CREATE TABLE users (id INTEGER);");

            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");
            session.execute("INSERT INTO users VALUES (2);");
            session.execute("COMMIT;");

            Catalog directView = Catalog.open(dm);
            assertEquals(2, directView.openTable("users").scanAll().size(),
                "COMMIT must flush every buffered dirty page before returning");
        } finally {
            Files.deleteIfExists(tmp);
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
            Session session = Session.bootstrap(dm);
            session.execute("CREATE TABLE users (id INTEGER);");

            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");
            session.execute("ROLLBACK;");

            Catalog directView = Catalog.open(dm);
            assertTrue(directView.openTable("users").scanAll().isEmpty(),
                "a rolled-back transaction's writes must never reach the buffer pool, let alone disk");
        } finally {
            Files.deleteIfExists(tmp);
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
