package com.strata.transaction;

import static com.strata.testutil.Assert.*;
import static com.strata.transaction.ConcurrencyRegressionTest.count;
import static com.strata.transaction.ConcurrencyRegressionTest.withDb;

import com.strata.executor.ExecutionException;
import com.strata.lock.LockTimeoutException;
import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Statement-level atomicity, failure handling, and session cleanup (audit bugs 4 and 5). */
public class TransactionLifecycleTest {

    private static final String TOO_BIG = "x".repeat(5000); // larger than any page can hold

    /**
     * Bug 4: UPDATE removed the old row (and its index entry), then failed to re-insert the
     * oversized new version. Inside BEGIN, those partial changes stayed in the transaction and
     * COMMIT silently deleted the row.
     */
    public void testFailedStatementInsideTransactionLeavesNoTrace() throws Exception {
        withDb((dm, bp, lm, wal) -> {
            Session s = Session.bootstrap(dm, bp, lm, wal);
            s.execute("CREATE TABLE t (id INTEGER, v VARCHAR);");
            s.execute("CREATE INDEX idx_id ON t (id);");
            s.execute("INSERT INTO t VALUES (1, 'a');");

            s.execute("BEGIN;");
            s.execute("INSERT INTO t VALUES (2, 'b');"); // succeeds; must survive the later failure
            assertThrows(IllegalArgumentException.class, () -> s.execute("UPDATE t SET v = '" + TOO_BIG + "' WHERE id = 1;"));
            assertTrue(s.inTransaction(), "a failed statement must not end the transaction");
            s.execute("COMMIT;");

            assertEquals(1, count(s, "SELECT * FROM t WHERE v = 'a';"), "full scan: row 1 must be untouched");
            assertEquals(1, count(s, "SELECT * FROM t WHERE id = 1;"), "index: row 1's entry must be untouched");
            assertEquals(1, count(s, "SELECT * FROM t WHERE id = 2;"), "the earlier successful statement must be kept");
        });
    }

    public void testFailedDdlInsideTransactionIsUndoneIncludingTheCatalogCache() throws Exception {
        withDb((dm, bp, lm, wal) -> {
            Session s = Session.bootstrap(dm, bp, lm, wal);
            s.execute("CREATE TABLE t (id INTEGER);");
            s.execute("BEGIN;");
            assertThrows(ExecutionException.class, () -> s.execute("CREATE INDEX bad ON t (no_such_column);"));
            s.execute("CREATE INDEX good ON t (id);");
            s.execute("INSERT INTO t VALUES (5);");
            s.execute("COMMIT;");
            assertEquals(1, count(s, "SELECT * FROM t WHERE id = 5;"));
        });
    }

    public void testLockTimeoutInsideTransactionKeepsEarlierWorkAndLocks() throws Exception {
        withDb(200, (dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            Session s2 = Session.open(dm, bp, lm, wal);
            s1.execute("CREATE TABLE a (id INTEGER);");
            s1.execute("CREATE TABLE b (id INTEGER);");

            s2.execute("BEGIN;");
            s2.execute("INSERT INTO b VALUES (1);");

            s1.execute("BEGIN;");
            s1.execute("INSERT INTO a VALUES (1);");
            assertThrows(LockTimeoutException.class, () -> s1.execute("INSERT INTO b VALUES (2);"));
            s2.execute("COMMIT;");
            s1.execute("INSERT INTO b VALUES (2);"); // retry after the other transaction finished
            s1.execute("COMMIT;");

            assertEquals(1, count(s1, "SELECT * FROM a;"));
            assertEquals(2, count(s1, "SELECT * FROM b;"));
        });
    }

    public void testFailedAutocommitStatementReleasesItsLocks() throws Exception {
        withDb(200, (dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            Session s2 = Session.open(dm, bp, lm, wal);
            s1.execute("CREATE TABLE t (id INTEGER, v VARCHAR);");
            assertThrows(IllegalArgumentException.class, () -> s1.execute("INSERT INTO t VALUES (1, '" + TOO_BIG + "');"));
            s2.execute("INSERT INTO t VALUES (2, 'ok');"); // would time out if s1 still held t
            assertEquals(1, count(s2, "SELECT * FROM t;"));
        });
    }

    /** Bug 5 at the session level: closing a session that shares the database rolls back and unlocks. */
    public void testClosingASharedSessionRollsBackAndReleasesLocksButKeepsResourcesOpen() throws Exception {
        withDb(200, (dm, bp, lm, wal) -> {
            Session s1 = Session.bootstrap(dm, bp, lm, wal);
            s1.execute("CREATE TABLE t (id INTEGER);");
            s1.execute("BEGIN;");
            s1.execute("INSERT INTO t VALUES (1);");
            s1.close();

            Session s2 = Session.open(dm, bp, lm, wal);
            assertEquals(0, count(s2, "SELECT * FROM t;"), "the open transaction must have been rolled back");
            s2.execute("INSERT INTO t VALUES (2);"); // the lock must have been released, and dm/wal still open
            assertEquals(1, count(s2, "SELECT * FROM t;"));
        });
    }

    public void testStatementSavepointRestoresOverlayPagesMutatedInPlace() throws IOException {
        Path tmp = Files.createTempFile("stratadb-savepoint", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            int existing = dm.allocatePage();
            Transaction txn = new Transaction(dm);
            Page p = txn.readPage(existing);
            p.putInt(0, 1);
            txn.writePage(existing, p);

            txn.beginStatement();
            txn.readPage(existing).putInt(0, 2); // mutate the live overlay object, without writePage
            int fresh = txn.allocatePage();
            txn.rollbackStatement();

            assertEquals(1, txn.readPage(existing).getInt(0), "in-place mutation during the statement must be undone");
            txn.commit();
            assertEquals(1, dm.readPage(existing).getInt(0));
            assertEquals(0, dm.readPage(fresh).getInt(0));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
