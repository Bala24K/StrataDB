package com.strata.transaction;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.recovery.WriteAheadLog;
import com.strata.executor.ExecutionResult;
import com.strata.lock.LockManager;
import com.strata.lock.LockTimeoutException;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SessionConcurrencyTest {

    public void testTwoSessionsCanReadTheSameTableConcurrently() throws IOException {
        withSharedDatabase((tmp, dm, bufferPool, lockManager, wal) -> {
            Session setup = Session.bootstrap(dm, bufferPool, lockManager, wal);
            setup.execute("CREATE TABLE t (id INTEGER);");
            setup.execute("INSERT INTO t VALUES (1);");

            Session session1 = Session.open(dm, bufferPool, lockManager, wal);
            Session session2 = Session.open(dm, bufferPool, lockManager, wal);

            session1.execute("BEGIN;");
            session1.execute("SELECT * FROM t;"); // holds a shared lock until COMMIT/ROLLBACK

            // a second session's concurrent SELECT (also shared) must succeed without blocking
            ExecutionResult.Rows result = (ExecutionResult.Rows) session2.execute("SELECT * FROM t;");
            assertEquals(1, result.rows().size());

            session1.execute("COMMIT;");
        });
    }

    public void testWriterBlocksAnotherSessionsWriterUntilCommit() throws IOException {
        withSharedDatabase((tmp, dm, bufferPool, lockManager, wal) -> {
            Session setup = Session.bootstrap(dm, bufferPool, lockManager, wal);
            setup.execute("CREATE TABLE t (id INTEGER);");

            Session writer1 = Session.open(dm, bufferPool, lockManager, wal);
            writer1.execute("BEGIN;");
            writer1.execute("INSERT INTO t VALUES (1);"); // holds an exclusive lock on t until COMMIT

            Session writer2 = Session.open(dm, bufferPool, lockManager, wal);
            assertThrows(LockTimeoutException.class, () -> writer2.execute("INSERT INTO t VALUES (2);"),
                "a second session must not be able to write to a table another session is holding exclusively");

            writer1.execute("COMMIT;");

            // now that writer1 released its lock, writer2 must succeed
            writer2.execute("INSERT INTO t VALUES (2);");
            ExecutionResult.Rows result = (ExecutionResult.Rows) writer2.execute("SELECT * FROM t;");
            assertEquals(2, result.rows().size());
        });
    }

    public void testReaderBlocksAnotherSessionsWriter() throws IOException {
        withSharedDatabase((tmp, dm, bufferPool, lockManager, wal) -> {
            Session setup = Session.bootstrap(dm, bufferPool, lockManager, wal);
            setup.execute("CREATE TABLE t (id INTEGER);");
            setup.execute("INSERT INTO t VALUES (1);");

            Session reader = Session.open(dm, bufferPool, lockManager, wal);
            reader.execute("BEGIN;");
            reader.execute("SELECT * FROM t;"); // holds a shared lock until COMMIT

            Session writer = Session.open(dm, bufferPool, lockManager, wal);
            assertThrows(LockTimeoutException.class, () -> writer.execute("INSERT INTO t VALUES (2);"),
                "an open reader must block a concurrent writer on the same table");

            reader.execute("COMMIT;");
            writer.execute("INSERT INTO t VALUES (2);"); // now succeeds

            ExecutionResult.Rows result = (ExecutionResult.Rows) writer.execute("SELECT * FROM t;");
            assertEquals(2, result.rows().size());
        });
    }

    public void testLocksOnDifferentTablesDoNotInterfere() throws IOException {
        withSharedDatabase((tmp, dm, bufferPool, lockManager, wal) -> {
            Session setup = Session.bootstrap(dm, bufferPool, lockManager, wal);
            setup.execute("CREATE TABLE a (id INTEGER);");
            setup.execute("CREATE TABLE b (id INTEGER);");

            Session session1 = Session.open(dm, bufferPool, lockManager, wal);
            session1.execute("BEGIN;");
            session1.execute("INSERT INTO a VALUES (1);"); // exclusive lock on 'a' only

            Session session2 = Session.open(dm, bufferPool, lockManager, wal);
            session2.execute("INSERT INTO b VALUES (1);"); // unrelated table — must not block

            session1.execute("COMMIT;");
        });
    }

    // ---- helper ----

    @FunctionalInterface
    private interface SharedDatabaseTest {
        void run(Path tmp, DiskManager diskManager, BufferPool bufferPool, LockManager lockManager, WriteAheadLog wal) throws IOException;
    }

    private static void withSharedDatabase(SharedDatabaseTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-concurrency-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            // Every session sharing this database must also share one BufferPool (cache
            // coherence), one LockManager, and one WriteAheadLog (a single append position).
            // A short lock timeout keeps the blocked-request tests fast rather than waiting out
            // the multi-second default.
            BufferPool bufferPool = new BufferPool(diskManager, 64);
            LockManager lockManager = new LockManager(200);
            WriteAheadLog wal = Session.prepareWal(diskManager);
            test.run(tmp, diskManager, bufferPool, lockManager, wal);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
