package com.strata.catalog;

import static com.strata.testutil.Assert.*;

import com.strata.storage.HeapTable;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

public class CatalogTest {

    public void testBootstrapOnEmptyFileCreatesOnlyTheCatalogsOwnPage() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);

            assertEquals(1, diskManager.getNumPages(), "bootstrapping a fresh file allocates exactly the catalog's own page");
            assertTrue(catalog.listTableNames().isEmpty(), "a freshly bootstrapped catalog must have no user tables yet");
        });
    }

    public void testBootstrapOnNonEmptyFileThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            diskManager.allocatePage(); // simulate a file that already has content
            assertThrows(IllegalStateException.class, () -> Catalog.bootstrap(diskManager),
                "bootstrap() must refuse a non-empty file — use open() instead");
        });
    }

    public void testOpenOnEmptyFileThrows() throws IOException {
        withTempFile((tmp, diskManager) ->
            assertThrows(IllegalStateException.class, () -> Catalog.open(diskManager),
                "open() must refuse an empty file — use bootstrap() instead")
        );
    }

    public void testCreateTableRegistersAndCanBeLookedUp() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            List<ColumnDefinition> columns = List.of(
                new ColumnDefinition("id", DataType.INTEGER),
                new ColumnDefinition("name", DataType.VARCHAR)
            );

            catalog.createTable("users", columns);

            assertTrue(catalog.tableExists("users"));
            assertEquals(List.of("users"), catalog.listTableNames());

            Optional<TableInfo> info = catalog.getTable("users");
            assertTrue(info.isPresent());
            assertEquals(columns, info.get().getColumns());
        });
    }

    public void testCreatingDuplicateTableNameThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            List<ColumnDefinition> columns = List.of(new ColumnDefinition("id", DataType.INTEGER));
            catalog.createTable("users", columns);

            assertThrows(IllegalArgumentException.class, () -> catalog.createTable("users", columns),
                "creating a table with a name that already exists must fail");
        });
    }

    public void testCreatingTableWithNoColumnsThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            assertThrows(IllegalArgumentException.class, () -> catalog.createTable("empty", List.of()),
                "a table must have at least one column");
        });
    }

    public void testGetTableForUnknownNameIsEmpty() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            assertTrue(catalog.getTable("ghost").isEmpty());
        });
    }

    public void testOpenTableForUnknownNameThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            assertThrows(NoSuchElementException.class, () -> catalog.openTable("ghost"),
                "opening a table that was never created must fail clearly");
        });
    }

    public void testOpenTableReturnsAWorkingHeapTable() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));

            HeapTable users = catalog.openTable("users");
            users.insert("row one".getBytes());
            users.insert("row two".getBytes());

            assertEquals(2, users.scanAll().size(), "the HeapTable returned by openTable must actually work for reads/writes");
        });
    }

    public void testCatalogAndDataPersistAcrossRestart() throws IOException {
        Path tmp = Files.createTempFile("stratadb-catalog-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (DiskManager dm1 = new DiskManager(tmp)) {
                Catalog catalog = Catalog.bootstrap(dm1);
                catalog.createTable("users", List.of(
                    new ColumnDefinition("id", DataType.INTEGER),
                    new ColumnDefinition("name", DataType.VARCHAR)
                ));
                catalog.createTable("orders", List.of(
                    new ColumnDefinition("order_id", DataType.BIGINT),
                    new ColumnDefinition("total_cents", DataType.BIGINT)
                ));
                catalog.openTable("users").insert("alice".getBytes());
            } // simulates the process exiting

            try (DiskManager dm2 = new DiskManager(tmp)) {
                Catalog reopened = Catalog.open(dm2);

                assertEquals(List.of("users", "orders"), reopened.listTableNames(),
                    "both tables must still be known after a restart");

                TableInfo usersInfo = reopened.getTable("users").orElseThrow();
                assertEquals(2, usersInfo.getColumns().size());
                assertEquals(DataType.VARCHAR, usersInfo.getColumns().get(1).getType());

                List<byte[]> rows = reopened.openTable("users").scanAll();
                assertEquals(1, rows.size(), "row data inserted before the restart must still be there");
                assertArrayEquals("alice".getBytes(), rows.get(0));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- indexes (Phase 10) ----

    public void testCreateIndexRegistersItAndCanBeLookedUp() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));

            catalog.createIndex("idx_users_id", "users", "id", 8);

            assertTrue(catalog.indexExists("idx_users_id"));
            Optional<IndexInfo> found = catalog.findIndexForColumn("users", "id");
            assertTrue(found.isPresent());
            assertEquals("idx_users_id", found.get().indexName());
        });
    }

    public void testCreateIndexOnUnknownTableThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            assertThrows(IllegalArgumentException.class,
                () -> catalog.createIndex("idx", "ghosts", "id", 8));
        });
    }

    public void testCreateIndexOnUnknownColumnThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            assertThrows(IllegalArgumentException.class,
                () -> catalog.createIndex("idx", "users", "ghost_column", 8));
        });
    }

    public void testCreateDuplicateIndexNameThrows() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            catalog.createIndex("idx", "users", "id", 8);
            assertThrows(IllegalArgumentException.class,
                () -> catalog.createIndex("idx", "users", "id", 8));
        });
    }

    public void testGetIndexesForTableReturnsOnlyThatTablesIndexes() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            catalog.createTable("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER), new ColumnDefinition("name", DataType.VARCHAR)));
            catalog.createTable("orders", List.of(new ColumnDefinition("id", DataType.INTEGER)));

            catalog.createIndex("idx_users_id", "users", "id", 8);
            catalog.createIndex("idx_users_name", "users", "name", 8);
            catalog.createIndex("idx_orders_id", "orders", "id", 8);

            assertEquals(2, catalog.getIndexesForTable("users").size());
            assertEquals(1, catalog.getIndexesForTable("orders").size());
        });
    }

    public void testOpenIndexReturnsAWorkingHashIndex() throws IOException {
        withTempFile((tmp, diskManager) -> {
            Catalog catalog = Catalog.bootstrap(diskManager);
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            IndexInfo info = catalog.createIndex("idx_users_id", "users", "id", 8);

            var index = catalog.openIndex(info);
            index.insert(42, new com.strata.storage.RecordId(1, 0));

            assertEquals(List.of(new com.strata.storage.RecordId(1, 0)), index.search(42));
        });
    }

    public void testIndexesAndTablesPersistAcrossRestart() throws IOException {
        Path tmp = Files.createTempFile("stratadb-catalog-index-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (DiskManager dm1 = new DiskManager(tmp)) {
                Catalog catalog = Catalog.bootstrap(dm1);
                catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
                IndexInfo info = catalog.createIndex("idx_users_id", "users", "id", 8);
                catalog.openIndex(info).insert(7, new com.strata.storage.RecordId(2, 1));
            }

            try (DiskManager dm2 = new DiskManager(tmp)) {
                Catalog reopened = Catalog.open(dm2);
                assertTrue(reopened.indexExists("idx_users_id"));
                IndexInfo info = reopened.findIndexForColumn("users", "id").orElseThrow();
                assertEquals(List.of(new com.strata.storage.RecordId(2, 1)), reopened.openIndex(info).search(7));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- helper ----

    @FunctionalInterface
    private interface DiskManagerTest {
        void run(Path tmp, DiskManager diskManager) throws IOException;
    }

    private static void withTempFile(DiskManagerTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-catalog-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            test.run(tmp, diskManager);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
