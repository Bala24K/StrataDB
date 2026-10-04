package com.strata.executor;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.Catalog;
import com.strata.parser.Parser;
import com.strata.parser.ast.Statement;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ExecutorTest {

    // ---- CREATE TABLE ----

    public void testCreateTableReturnsTableCreatedResult() throws IOException {
        withExecutor((tmp, executor) -> {
            ExecutionResult result = run(executor, "CREATE TABLE users (id INTEGER);");
            assertEquals(new ExecutionResult.TableCreated("users"), result);
        });
    }

    public void testCreatingDuplicateTableThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(IllegalArgumentException.class, () -> run(executor, "CREATE TABLE users (id INTEGER);"),
                "the catalog's own duplicate-name check propagates as-is");
        });
    }

    // ---- INSERT ----

    public void testInsertReturnsRowsInsertedCountOne() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            ExecutionResult result = run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            assertEquals(new ExecutionResult.RowsInserted(1), result);
        });
    }

    public void testInsertAndSelectStarRoundTrip() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users;");
            assertEquals(List.of("id", "name", "balance", "active"), result.columnNames());
            assertEquals(List.of(1, "Alice", 1000L, true), result.rows().get(0).values());
        });
    }

    public void testInsertWithExplicitColumnListLeavesOthersNull() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users (id, name) VALUES (1, 'Alice');");

            List<Object> row = ((ExecutionResult.Rows) run(executor, "SELECT * FROM users;")).rows().get(0).values();
            assertEquals(1, row.get(0));
            assertEquals("Alice", row.get(1));
            assertEquals(null, row.get(2), "balance was omitted and must default to NULL");
            assertEquals(null, row.get(3), "active was omitted and must default to NULL");
        });
    }

    public void testInsertWrongNumberOfPositionalValuesThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(ExecutionException.class, () -> run(executor, "INSERT INTO users VALUES (1, 'Alice');"));
        });
    }

    public void testInsertTypeMismatchThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(ExecutionException.class,
                () -> run(executor, "INSERT INTO users VALUES ('not a number', 'Alice', 1000, TRUE);"));
        });
    }

    public void testInsertIntegerOverflowThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(ExecutionException.class,
                () -> run(executor, "INSERT INTO users VALUES (99999999999, 'Alice', 1000, TRUE);"),
                "99999999999 overflows a 32-bit INTEGER column");
        });
    }

    public void testInsertIntoNonexistentTableThrows() throws IOException {
        withExecutor((tmp, executor) ->
            assertThrows(ExecutionException.class, () -> run(executor, "INSERT INTO ghosts VALUES (1);")));
    }

    // ---- SELECT: WHERE ----

    public void testSelectWithWhereEquals() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, FALSE);");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 2;");
            assertEquals(1, result.rows().size());
            assertEquals("Bob", result.rows().get(0).values().get(1));
        });
    }

    public void testSelectWithComparisonOperators() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, FALSE);");
            run(executor, "INSERT INTO users VALUES (3, 'Carol', 3000, TRUE);");

            assertEquals(2, rowsOf(run(executor, "SELECT * FROM users WHERE balance > 1000;")).size());
            assertEquals(1, rowsOf(run(executor, "SELECT * FROM users WHERE balance >= 3000;")).size());
            assertEquals(2, rowsOf(run(executor, "SELECT * FROM users WHERE id <> 2;")).size());
        });
    }

    public void testSelectWithAndOr() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, FALSE);");
            run(executor, "INSERT INTO users VALUES (3, 'Carol', 3000, TRUE);");

            ExecutionResult.Rows and = (ExecutionResult.Rows) run(executor, "SELECT name FROM users WHERE active = TRUE AND balance > 2000;");
            assertEquals(List.of(List.of("Carol")), and.rows().stream().map(Row::values).toList());

            ExecutionResult.Rows or = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 1 OR id = 3;");
            assertEquals(2, or.rows().size());
        });
    }

    public void testParenthesesOverridePrecedenceInExecution() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");  // active, low balance
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, FALSE);");   // inactive, mid balance
            run(executor, "INSERT INTO users VALUES (3, 'Carol', 5000, FALSE);"); // inactive, high balance

            // "active AND (id=2 OR balance>4000)" must match nobody (Alice fails the OR side, Bob/Carol fail active)
            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor,
                "SELECT * FROM users WHERE active = TRUE AND (id = 2 OR balance > 4000);");
            assertTrue(result.rows().isEmpty());
        });
    }

    public void testNullComparisonIsUnknownNotTrue() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users (id, name) VALUES (1, 'NoBalance');"); // balance is NULL
            run(executor, "INSERT INTO users VALUES (2, 'HasBalance', 500, TRUE);");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE balance > 0;");
            assertEquals(1, result.rows().size(), "a NULL balance must not satisfy a '>' comparison");
            assertEquals("HasBalance", result.rows().get(0).values().get(1));
        });
    }

    public void testFalseAndUnknownIsFalseNotUnknown() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            // active=FALSE (known false) AND balance>0 (unknown, balance is NULL) must be FALSE, excluding the row
            run(executor, "INSERT INTO users (id, name, active) VALUES (1, 'x', FALSE);");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor,
                "SELECT * FROM users WHERE active = FALSE AND balance > 0;");
            assertTrue(result.rows().isEmpty(), "FALSE AND UNKNOWN must be FALSE per SQL's three-valued logic");
        });
    }

    public void testSelectFromNonexistentTableThrows() throws IOException {
        withExecutor((tmp, executor) ->
            assertThrows(ExecutionException.class, () -> run(executor, "SELECT * FROM ghosts;")));
    }

    public void testSelectNonexistentColumnThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(ExecutionException.class, () -> run(executor, "SELECT ghost_column FROM users;"));
        });
    }

    // ---- SELECT: projection, ORDER BY, LIMIT ----

    public void testSelectProjectsSpecificColumnsInRequestedOrder() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT name, id FROM users;");
            assertEquals(List.of("name", "id"), result.columnNames());
            assertEquals(List.of("Alice", 1), result.rows().get(0).values());
        });
    }

    public void testSelectOrderByAscendingAndDescending() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 3000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (3, 'Carol', 2000, TRUE);");

            ExecutionResult.Rows asc = (ExecutionResult.Rows) run(executor, "SELECT name FROM users ORDER BY balance ASC;");
            assertEquals(List.of(List.of("Bob"), List.of("Carol"), List.of("Alice")),
                asc.rows().stream().map(Row::values).toList());

            ExecutionResult.Rows desc = (ExecutionResult.Rows) run(executor, "SELECT name FROM users ORDER BY balance DESC;");
            assertEquals(List.of(List.of("Alice"), List.of("Carol"), List.of("Bob")),
                desc.rows().stream().map(Row::values).toList());
        });
    }

    public void testSelectLimit() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            for (int i = 1; i <= 5; i++) {
                run(executor, "INSERT INTO users VALUES (" + i + ", 'user" + i + "', " + (i * 100) + ", TRUE);");
            }

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT id FROM users ORDER BY id LIMIT 2;");
            assertEquals(List.of(List.of(1), List.of(2)), result.rows().stream().map(Row::values).toList());
        });
    }

    // ---- UPDATE ----

    public void testUpdateModifiesOnlyMatchingRows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, TRUE);");

            ExecutionResult result = run(executor, "UPDATE users SET active = FALSE WHERE id = 1;");
            assertEquals(new ExecutionResult.RowsUpdated(1), result);

            ExecutionResult.Rows rows = (ExecutionResult.Rows) run(executor, "SELECT id, active FROM users ORDER BY id;");
            assertEquals(false, rows.rows().get(0).values().get(1));
            assertEquals(true, rows.rows().get(1).values().get(1));
        });
    }

    public void testUpdateWithoutWhereUpdatesEveryRow() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, FALSE);");

            ExecutionResult result = run(executor, "UPDATE users SET active = TRUE;");
            assertEquals(new ExecutionResult.RowsUpdated(2), result);
        });
    }

    public void testUpdateLeavesUnmentionedColumnsUnchanged() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");

            run(executor, "UPDATE users SET balance = 5000 WHERE id = 1;");

            List<Object> row = ((ExecutionResult.Rows) run(executor, "SELECT * FROM users;")).rows().get(0).values();
            assertEquals("Alice", row.get(1), "name wasn't in the SET clause and must be untouched");
            assertEquals(5000L, row.get(2));
        });
    }

    public void testUpdateUnknownColumnThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(ExecutionException.class, () -> run(executor, "UPDATE users SET ghost = TRUE;"));
        });
    }

    // ---- DELETE ----

    public void testDeleteRemovesOnlyMatchingRows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, TRUE);");

            ExecutionResult result = run(executor, "DELETE FROM users WHERE id = 1;");
            assertEquals(new ExecutionResult.RowsDeleted(1), result);

            ExecutionResult.Rows rows = (ExecutionResult.Rows) run(executor, "SELECT * FROM users;");
            assertEquals(1, rows.rows().size());
            assertEquals("Bob", rows.rows().get(0).values().get(1));
        });
    }

    public void testDeleteWithoutWhereDeletesEveryRow() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, TRUE);");

            run(executor, "DELETE FROM users;");

            assertTrue(rowsOf(run(executor, "SELECT * FROM users;")).isEmpty());
        });
    }

    // ---- end-to-end ----

    public void testFullWorkflowEndToEnd() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, TRUE);");
            run(executor, "INSERT INTO users VALUES (3, 'Carol', 3000, FALSE);");

            run(executor, "UPDATE users SET balance = 2500 WHERE name = 'Bob';");
            run(executor, "DELETE FROM users WHERE active = FALSE;"); // removes Carol

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT name, balance FROM users ORDER BY balance DESC;");
            assertEquals(List.of(List.of("Bob", 2500L), List.of("Alice", 1000L)),
                result.rows().stream().map(Row::values).toList());
        });
    }

    public void testDataPersistsAcrossRestartThroughFullStack() throws IOException {
        Path tmp = Files.createTempFile("stratadb-executor-restart-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (DiskManager dm1 = new DiskManager(tmp)) {
                Executor executor = new Executor(Catalog.bootstrap(dm1));
                run(executor, "CREATE TABLE users (id INTEGER, name VARCHAR);");
                run(executor, "INSERT INTO users VALUES (1, 'Alice');");
            } // simulates the process exiting

            try (DiskManager dm2 = new DiskManager(tmp)) {
                Executor executor = new Executor(Catalog.open(dm2));
                ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users;");
                assertEquals(1, result.rows().size());
                assertEquals("Alice", result.rows().get(0).values().get(1));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- CREATE INDEX and index-backed queries (Phase 10) ----

    public void testCreateIndexReturnsIndexCreatedResult() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            ExecutionResult result = run(executor, "CREATE INDEX idx_users_id ON users (id);");
            assertEquals(new ExecutionResult.IndexCreated("idx_users_id"), result);
        });
    }

    public void testCreateIndexPopulatesFromExistingRows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, TRUE);");

            run(executor, "CREATE INDEX idx_users_id ON users (id);");

            // an equality lookup on the newly-indexed column must find a row that existed before the index did
            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 1;");
            assertEquals(1, result.rows().size());
            assertEquals("Alice", result.rows().get(0).values().get(1));
        });
    }

    public void testSelectResultsAreIdenticalWithOrWithoutAnIndex() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "INSERT INTO users VALUES (2, 'Bob', 2000, FALSE);");
            run(executor, "INSERT INTO users VALUES (3, 'Carol', 3000, TRUE);");

            ExecutionResult.Rows beforeIndex = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 2;");
            run(executor, "CREATE INDEX idx_users_id ON users (id);");
            ExecutionResult.Rows afterIndex = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 2;");

            assertEquals(beforeIndex.rows().stream().map(Row::values).toList(),
                afterIndex.rows().stream().map(Row::values).toList(),
                "planning a query differently must never change its results");
        });
    }

    public void testInsertAfterIndexCreationIsFoundByIndexScan() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "CREATE INDEX idx_users_id ON users (id);");
            run(executor, "INSERT INTO users VALUES (5, 'NewUser', 100, TRUE);");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 5;");
            assertEquals(1, result.rows().size(), "a row inserted after the index existed must still be found");
        });
    }

    public void testDeletedRowIsNoLongerFoundByIndexScan() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "CREATE INDEX idx_users_id ON users (id);");

            run(executor, "DELETE FROM users WHERE id = 1;");

            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 1;");
            assertTrue(result.rows().isEmpty(), "a deleted row must not still be findable via the index");
        });
    }

    public void testUpdatedRowIsFoundByNewValueNotOldValue() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "CREATE INDEX idx_users_id ON users (id);");

            run(executor, "UPDATE users SET id = 99 WHERE id = 1;");

            ExecutionResult.Rows oldIdResult = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 1;");
            assertTrue(oldIdResult.rows().isEmpty(), "the old indexed value must no longer find the row");

            ExecutionResult.Rows newIdResult = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 99;");
            assertEquals(1, newIdResult.rows().size(), "the new indexed value must find the row");
        });
    }

    public void testIndexScanCombinedWithAndStillAppliesFullFilter() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            run(executor, "INSERT INTO users VALUES (1, 'Alice', 1000, TRUE);");
            run(executor, "CREATE INDEX idx_users_id ON users (id);");

            // id=1 matches via the index, but active=FALSE must still exclude it via the residual filter
            ExecutionResult.Rows result = (ExecutionResult.Rows) run(executor, "SELECT * FROM users WHERE id = 1 AND active = FALSE;");
            assertTrue(result.rows().isEmpty(), "the index only narrows candidates — the full WHERE clause must still apply");
        });
    }

    public void testCreatingIndexOnUnknownTableThrows() throws IOException {
        withExecutor((tmp, executor) ->
            assertThrows(ExecutionException.class, () -> run(executor, "CREATE INDEX idx ON ghosts (id);")));
    }

    public void testCreatingIndexOnUnknownColumnThrows() throws IOException {
        withExecutor((tmp, executor) -> {
            createUsersTable(executor);
            assertThrows(ExecutionException.class, () -> run(executor, "CREATE INDEX idx ON users (ghost_column);"));
        });
    }

    // ---- helpers ----

    private static ExecutionResult run(Executor executor, String sql) throws IOException {
        Statement statement = Parser.parse(sql);
        return executor.execute(statement);
    }

    private static List<Row> rowsOf(ExecutionResult result) {
        return ((ExecutionResult.Rows) result).rows();
    }

    private static void createUsersTable(Executor executor) throws IOException {
        run(executor, "CREATE TABLE users (id INTEGER, name VARCHAR, balance BIGINT, active BOOLEAN);");
    }

    @FunctionalInterface
    private interface ExecutorTestCase {
        void run(Path tmp, Executor executor) throws IOException;
    }

    private static void withExecutor(ExecutorTestCase test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-executor-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            Executor executor = new Executor(Catalog.bootstrap(diskManager));
            test.run(tmp, executor);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
