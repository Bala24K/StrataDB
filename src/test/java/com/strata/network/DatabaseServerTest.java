package com.strata.network;

import static com.strata.testutil.Assert.*;

import com.strata.lock.LockManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DatabaseServerTest {

    public void testCreateTableInsertAndSelectOverTheWire() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client = connect(server)) {
                assertEquals(List.of("OK TABLE_CREATED users"),
                    client.execute("CREATE TABLE users (id INTEGER, name VARCHAR);"));
                assertEquals(List.of("OK ROWS_INSERTED 1"),
                    client.execute("INSERT INTO users VALUES (1, 'Alice');"));

                List<String> response = client.execute("SELECT * FROM users;");
                assertEquals(List.of("OK ROWS 1", "id\tname", "1\tAlice"), response);
            }
        });
    }

    public void testNullIsRenderedAsLiteralText() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client = connect(server)) {
                client.execute("CREATE TABLE t (id INTEGER, name VARCHAR);");
                client.execute("INSERT INTO t (id) VALUES (1);"); // name omitted -> NULL

                List<String> response = client.execute("SELECT * FROM t;");
                assertEquals(List.of("OK ROWS 1", "id\tname", "1\tNULL"), response);
            }
        });
    }

    public void testMalformedSqlReturnsAnErrorAndConnectionStaysUsable() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client = connect(server)) {
                List<String> errorResponse = client.execute("NOT VALID SQL AT ALL;");
                assertEquals(1, errorResponse.size());
                assertTrue(errorResponse.get(0).startsWith("ERROR ParseException"),
                    "a parse error must be reported as an ERROR line, not silently dropped");

                // the same connection must still work for a subsequent, valid statement
                assertEquals(List.of("OK TABLE_CREATED t"), client.execute("CREATE TABLE t (id INTEGER);"));
            }
        });
    }

    public void testUnknownTableReturnsAnExecutionError() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client = connect(server)) {
                List<String> response = client.execute("SELECT * FROM ghosts;");
                assertEquals(1, response.size());
                assertTrue(response.get(0).startsWith("ERROR ExecutionException"));
            }
        });
    }

    public void testExplicitTransactionOverTheWire() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client = connect(server)) {
                client.execute("CREATE TABLE accounts (id INTEGER, balance BIGINT);");
                client.execute("INSERT INTO accounts VALUES (1, 1000);");

                assertEquals(List.of("OK TRANSACTION_STARTED"), client.execute("BEGIN;"));
                client.execute("UPDATE accounts SET balance = 900 WHERE id = 1;");
                assertEquals(List.of("OK TRANSACTION_COMMITTED"), client.execute("COMMIT;"));

                assertEquals(List.of("OK ROWS 1", "balance", "900"),
                    client.execute("SELECT balance FROM accounts;"));
            }
        });
    }

    public void testQuitClosesTheConnectionCleanly() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client = connect(server)) {
                client.execute("CREATE TABLE t (id INTEGER);");
                client.quit(); // must not throw, and must not affect the server itself
            }
            // a fresh connection to the same server must still work afterward
            try (DatabaseClient client2 = connect(server)) {
                assertEquals(List.of("OK ROWS_INSERTED 1"), client2.execute("INSERT INTO t VALUES (1);"));
            }
        });
    }

    public void testTwoConcurrentClientsSeeEachOthersCommittedWrites() throws IOException {
        withServer((tmp, server) -> {
            try (DatabaseClient client1 = connect(server);
                 DatabaseClient client2 = connect(server)) {
                client1.execute("CREATE TABLE t (id INTEGER);");
                client1.execute("INSERT INTO t VALUES (1);");

                // a completely independent connection must see client1's committed insert
                List<String> response = client2.execute("SELECT * FROM t;");
                assertEquals(List.of("OK ROWS 1", "id", "1"), response);
            }
        });
    }

    public void testOneClientsOpenTransactionBlocksAnotherClientsWrite() throws IOException {
        Path tmp = Files.createTempFile("stratadb-network-test", ".db");
        Files.deleteIfExists(tmp);
        try (DatabaseServer server = DatabaseServer.start(tmp, 0, new LockManager(300))) {
            try (DatabaseClient setup = connect(server)) {
                setup.execute("CREATE TABLE t (id INTEGER);");
            }

            try (DatabaseClient writer1 = connect(server);
                 DatabaseClient writer2 = connect(server)) {
                writer1.execute("BEGIN;");
                writer1.execute("INSERT INTO t VALUES (1);"); // holds an exclusive lock on t

                List<String> blockedResponse = writer2.execute("INSERT INTO t VALUES (2);");
                assertEquals(1, blockedResponse.size());
                assertTrue(blockedResponse.get(0).startsWith("ERROR LockTimeoutException"),
                    "a second client must not be able to write to a table the first is still holding exclusively");

                writer1.execute("COMMIT;");

                assertEquals(List.of("OK ROWS_INSERTED 1"), writer2.execute("INSERT INTO t VALUES (2);"));
            }
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp.toString() + ".wal"));
        }
    }

    public void testDataPersistsAcrossAServerRestart() throws IOException {
        Path tmp = Files.createTempFile("stratadb-network-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (DatabaseServer server = DatabaseServer.start(tmp, 0)) {
                try (DatabaseClient client = new DatabaseClient("localhost", server.getPort())) {
                    client.execute("CREATE TABLE users (id INTEGER, name VARCHAR);");
                    client.execute("INSERT INTO users VALUES (1, 'Alice');");
                }
            } // server closed — simulates a full process restart

            try (DatabaseServer server2 = DatabaseServer.start(tmp, 0);
                 DatabaseClient client2 = new DatabaseClient("localhost", server2.getPort())) {
                assertEquals(List.of("OK ROWS 1", "id\tname", "1\tAlice"), client2.execute("SELECT * FROM users;"));
            }
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp.toString() + ".wal"));
        }
    }

    public void testManyConcurrentClientsInsertingDoNotLoseWrites() throws Exception {
        Path tmp = Files.createTempFile("stratadb-network-test", ".db");
        Files.deleteIfExists(tmp);
        try (DatabaseServer server = DatabaseServer.start(tmp, 0)) {
            try (DatabaseClient setup = connect(server)) {
                setup.execute("CREATE TABLE counters (value INTEGER);");
            }

            int clientCount = 8;
            CountDownLatch done = new CountDownLatch(clientCount);
            AtomicReference<Exception> firstFailure = new AtomicReference<>();

            for (int i = 0; i < clientCount; i++) {
                int value = i;
                Thread thread = new Thread(() -> {
                    try (DatabaseClient client = new DatabaseClient("localhost", server.getPort())) {
                        client.execute("INSERT INTO counters VALUES (" + value + ");");
                    } catch (Exception e) {
                        firstFailure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
                thread.start();
            }

            assertTrue(done.await(10, TimeUnit.SECONDS), "all client threads must finish within the timeout");
            if (firstFailure.get() != null) {
                throw new AssertionError("a client thread failed", firstFailure.get());
            }

            try (DatabaseClient verifier = connect(server)) {
                List<String> response = verifier.execute("SELECT * FROM counters;");
                assertEquals("OK ROWS " + clientCount, response.get(0),
                    "every concurrent client's insert must have been applied — none lost");
            }
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp.toString() + ".wal"));
        }
    }

    // ---- helpers ----

    private static DatabaseClient connect(DatabaseServer server) throws IOException {
        return new DatabaseClient("localhost", server.getPort());
    }

    @FunctionalInterface
    private interface ServerTest {
        void run(Path tmp, DatabaseServer server) throws Exception;
    }

    private static void withServer(ServerTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-network-test", ".db");
        Files.deleteIfExists(tmp);
        try (DatabaseServer server = DatabaseServer.start(tmp, 0)) {
            test.run(tmp, server);
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new RuntimeException(e);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp.toString() + ".wal"));
        }
    }
}
