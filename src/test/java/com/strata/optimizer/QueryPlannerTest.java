package com.strata.optimizer;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.Catalog;
import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.parser.Parser;
import com.strata.parser.ast.Expr;
import com.strata.parser.ast.Statement;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class QueryPlannerTest {

    public void testNoWhereClauseIsAlwaysAFullScan() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            ScanPlan plan = planner.planScan("users", Optional.empty());
            assertEquals(new ScanPlan.FullScan("users"), plan);
        });
    }

    public void testEqualityOnAColumnWithNoIndexIsAFullScan() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE id = 5;"));
            assertEquals(new ScanPlan.FullScan("users"), plan);
        });
    }

    public void testEqualityOnAnIndexedColumnIsAnIndexScan() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            catalog.createIndex("idx_users_id", "users", "id", 8);

            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE id = 5;"));

            assertEquals(new ScanPlan.IndexScan("users", "idx_users_id", 5L), plan);
        });
    }

    public void testEqualityWithLiteralOnTheLeftIsStillRecognized() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            catalog.createIndex("idx_users_id", "users", "id", 8);

            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE 5 = id;"));

            assertEquals(new ScanPlan.IndexScan("users", "idx_users_id", 5L), plan);
        });
    }

    public void testNonEqualityComparisonOnIndexedColumnIsAFullScan() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(new ColumnDefinition("balance", DataType.BIGINT)));
            catalog.createIndex("idx_users_balance", "users", "balance", 8);

            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE balance > 100;"));

            assertEquals(new ScanPlan.FullScan("users"), plan, "a range predicate can't be answered by a hash index");
        });
    }

    public void testEqualityInsideAnAndChainIsFound() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER), new ColumnDefinition("active", DataType.BOOLEAN)));
            catalog.createIndex("idx_users_id", "users", "id", 8);

            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE active = TRUE AND id = 5;"));

            assertEquals(new ScanPlan.IndexScan("users", "idx_users_id", 5L), plan,
                "an indexable equality must be found even when it's not the first AND term");
        });
    }

    public void testEqualityInsideAnOrIsNotUsed() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER), new ColumnDefinition("active", DataType.BOOLEAN)));
            catalog.createIndex("idx_users_id", "users", "id", 8);

            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE id = 5 OR active = TRUE;"));

            assertEquals(new ScanPlan.FullScan("users"), plan, "OR can't be satisfied by a single index lookup");
        });
    }

    public void testEqualityAgainstNullIsNotUsed() throws IOException {
        withPlanner((tmp, catalog, planner) -> {
            catalog.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));
            catalog.createIndex("idx_users_id", "users", "id", 8);

            ScanPlan plan = planner.planScan("users", whereOf("SELECT * FROM users WHERE id = NULL;"));

            assertEquals(new ScanPlan.FullScan("users"), plan);
        });
    }

    // ---- helpers ----

    private static Optional<Expr> whereOf(String sql) {
        Statement.Select select = (Statement.Select) Parser.parse(sql);
        return select.where();
    }

    @FunctionalInterface
    private interface PlannerTest {
        void run(Path tmp, Catalog catalog, QueryPlanner planner) throws IOException;
    }

    private static void withPlanner(PlannerTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-queryplanner-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            Catalog catalog = Catalog.bootstrap(diskManager);
            QueryPlanner planner = new QueryPlanner(catalog);
            test.run(tmp, catalog, planner);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
