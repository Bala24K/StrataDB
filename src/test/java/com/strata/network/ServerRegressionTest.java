package com.strata.network;

import static com.strata.testutil.Assert.*;

import com.strata.lock.LockManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Regression tests for server-level bugs found in the 2026-11 audit (bugs 5 and 6). */
public class ServerRegressionTest {

    /**
     * Bug 5: a client that disconnected mid-transaction kept its locks forever; every other
     * client then timed out on that table until the server restarted.
     */
    public void testDisconnectMidTransactionRollsBackAndReleasesLocks() throws Exception {
        withServer(new LockManager(2_000), server -> {
            try (DatabaseClient setup = connect(server)) {
                setup.execute("CREATE TABLE t (id INTEGER);");
            }
            DatabaseClient abandoned = connect(server);
            abandoned.execute("BEGIN;");
            abandoned.execute("INSERT INTO t VALUES (1);");
            abandoned.close(); // no ROLLBACK, no QUIT

            try (DatabaseClient other = connect(server)) {
                // The server notices the disconnect asynchronously; allow a short window.
                List<String> response = List.of();
                for (int attempt = 0; attempt < 5; attempt++) {
                    response = other.execute("SELECT * FROM t;");
                    if (response.get(0).startsWith("OK")) break;
                }
                assertEquals("OK ROWS 0", response.get(0), "the abandoned insert must be rolled back and its lock released");
                assertEquals(List.of("OK ROWS_INSERTED 1"), other.execute("INSERT INTO t VALUES (2);"));
            }
        });
    }

    /** Bug 6: a VARCHAR value equal to the END sentinel desynchronised every later response. */
    public void testValueEqualToEndSentinelDoesNotBreakFraming() throws Exception {
        withServer(new LockManager(), server -> {
            try (DatabaseClient c = connect(server)) {
                c.execute("CREATE TABLE t (v VARCHAR);");
                c.execute("INSERT INTO t VALUES ('END');");
                c.execute("INSERT INTO t VALUES ('second');");
                assertEquals(List.of("OK ROWS 2", "v", "END", "second"), c.execute("SELECT * FROM t;"));
                assertEquals(List.of("OK ROWS 1", "v", "second"), c.execute("SELECT * FROM t WHERE v = 'second';"));
            }
        });
    }

    public void testTabsAndBackslashesInValuesAreEscaped() throws Exception {
        withServer(new LockManager(), server -> {
            try (DatabaseClient c = connect(server)) {
                c.execute("CREATE TABLE t (a VARCHAR, b INTEGER);");
                c.execute("INSERT INTO t VALUES ('x\ty\\z', 1);");
                List<String> rows = c.execute("SELECT * FROM t;");
                assertEquals("x\\ty\\\\z\t1", rows.get(2), "the embedded tab must not look like a column separator");
                assertEquals("x\ty\\z", Protocol.unescape(rows.get(2).split("\t")[0]));
            }
        });
    }

    // ---- helpers ----

    private static DatabaseClient connect(DatabaseServer server) throws Exception {
        return new DatabaseClient("localhost", server.getPort());
    }

    @FunctionalInterface
    private interface ServerTest {
        void run(DatabaseServer server) throws Exception;
    }

    private static void withServer(LockManager lockManager, ServerTest test) throws Exception {
        Path tmp = Files.createTempFile("stratadb-server-regression", ".db");
        Files.deleteIfExists(tmp);
        try (DatabaseServer server = DatabaseServer.start(tmp, 0, lockManager)) {
            test.run(server);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }
}
