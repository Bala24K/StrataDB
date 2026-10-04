package com.strata.transaction;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.Catalog;
import com.strata.executor.ExecutionResult;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SessionTest {

    public void testAutocommitStatementsPersistImmediately() throws IOException {
        withSession((tmp, session) -> {
            session.execute("CREATE TABLE users (id INTEGER, name VARCHAR);");
            session.execute("INSERT INTO users VALUES (1, 'Alice');");

            ExecutionResult.Rows result = (ExecutionResult.Rows) session.execute("SELECT * FROM users;");
            assertEquals(1, result.rows().size(), "an autocommit INSERT must be immediately visible");
        });
    }

    public void testExplicitTransactionIsInvisibleUntilCommitted() throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (DiskManager dm = new DiskManager(tmp)) {
                Session session = Session.bootstrap(dm);
                session.execute("CREATE TABLE users (id INTEGER);");

                session.execute("BEGIN;");
                session.execute("INSERT INTO users VALUES (1);");
                session.execute("INSERT INTO users VALUES (2);");

                // a second, independent transaction against the SAME underlying store must not see the uncommitted rows
                Transaction outsideTxn = new Transaction(dm);
                Catalog outsideView = Catalog.open(outsideTxn);
                List<byte[]> rows = outsideView.openTable("users").scanAll();
                assertTrue(rows.isEmpty(), "uncommitted rows must not be visible to a separate transaction");

                session.execute("COMMIT;");
            }

            try (DiskManager dm2 = new DiskManager(tmp)) {
                Catalog afterCommit = Catalog.open(dm2);
                assertEquals(2, afterCommit.openTable("users").scanAll().size(), "both rows must exist after COMMIT");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void testReadYourOwnWritesInsideAnExplicitTransaction() throws IOException {
        withSession((tmp, session) -> {
            session.execute("CREATE TABLE users (id INTEGER);");
            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");

            // a SELECT within the SAME still-open transaction must see the not-yet-committed insert
            ExecutionResult.Rows result = (ExecutionResult.Rows) session.execute("SELECT * FROM users;");
            assertEquals(1, result.rows().size(), "a transaction must see its own uncommitted writes");

            session.execute("ROLLBACK;");
        });
    }

    public void testRollbackDiscardsEveryStatementInTheTransaction() throws IOException {
        withSession((tmp, session) -> {
            session.execute("CREATE TABLE users (id INTEGER);");
            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");
            session.execute("INSERT INTO users VALUES (2);");
            session.execute("ROLLBACK;");

            ExecutionResult.Rows result = (ExecutionResult.Rows) session.execute("SELECT * FROM users;");
            assertTrue(result.rows().isEmpty(), "every statement since BEGIN must be discarded by ROLLBACK");
        });
    }

    public void testSessionRemainsUsableAfterARollback() throws IOException {
        withSession((tmp, session) -> {
            session.execute("CREATE TABLE users (id INTEGER);");
            session.execute("BEGIN;");
            session.execute("INSERT INTO users VALUES (1);");
            session.execute("ROLLBACK;");

            // autocommit statements after a rollback must still work normally
            session.execute("INSERT INTO users VALUES (2);");
            ExecutionResult.Rows result = (ExecutionResult.Rows) session.execute("SELECT * FROM users;");
            assertEquals(1, result.rows().size());
            assertEquals(2, result.rows().get(0).values().get(0));
        });
    }

    public void testCreateTableInsideATransactionRollsBackToo() throws IOException {
        withSession((tmp, session) -> {
            session.execute("BEGIN;");
            session.execute("CREATE TABLE ghosts (id INTEGER);");
            session.execute("ROLLBACK;");

            assertThrows(com.strata.executor.ExecutionException.class, () -> session.execute("SELECT * FROM ghosts;"),
                "a CREATE TABLE inside a rolled-back transaction must not exist afterward");
        });
    }

    public void testDeeplyNestedBeginThrows() throws IOException {
        withSession((tmp, session) -> {
            session.execute("BEGIN;");
            assertThrows(IllegalStateException.class, () -> session.execute("BEGIN;"),
                "nested BEGIN isn't supported without SAVEPOINT");
            session.execute("ROLLBACK;");
        });
    }

    public void testCommitWithoutActiveTransactionThrows() throws IOException {
        withSession((tmp, session) ->
            assertThrows(IllegalStateException.class, () -> session.execute("COMMIT;")));
    }

    public void testRollbackWithoutActiveTransactionThrows() throws IOException {
        withSession((tmp, session) ->
            assertThrows(IllegalStateException.class, () -> session.execute("ROLLBACK;")));
    }

    public void testFullTransactionalWorkflow() throws IOException {
        withSession((tmp, session) -> {
            session.execute("CREATE TABLE accounts (id INTEGER, balance BIGINT);");
            session.execute("INSERT INTO accounts VALUES (1, 1000);");
            session.execute("INSERT INTO accounts VALUES (2, 500);");

            // a "transfer": debit one account, credit another, atomically
            session.execute("BEGIN;");
            session.execute("UPDATE accounts SET balance = 900 WHERE id = 1;");
            session.execute("UPDATE accounts SET balance = 600 WHERE id = 2;");
            session.execute("COMMIT;");

            ExecutionResult.Rows result = (ExecutionResult.Rows) session.execute("SELECT balance FROM accounts ORDER BY id;");
            assertEquals(List.of(List.of(900L), List.of(600L)),
                result.rows().stream().map(com.strata.executor.Row::values).toList());
        });
    }

    // ---- helper ----

    @FunctionalInterface
    private interface SessionTestCase {
        void run(Path tmp, Session session) throws IOException;
    }

    private static void withSession(SessionTestCase test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-session-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            Session session = Session.bootstrap(diskManager);
            test.run(tmp, session);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
