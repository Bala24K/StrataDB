package com.strata.optimizer;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.Catalog;
import com.strata.engine.Database;
import com.strata.executor.ExecutionException;
import com.strata.executor.ExecutionResult;
import com.strata.executor.Row;
import com.strata.parser.Parser;
import com.strata.parser.ast.Statement;
import com.strata.storage.page.DiskManager;
import com.strata.transaction.Session;
import com.strata.transaction.Transaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Range planning on B+Tree indexes, checked against full scans of the same data. */
public class RangePlanTest {

    public void testPlannerChoosesTheRightAccessPath() throws Exception {
        withDb(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER, h INTEGER, name VARCHAR);");
            s.execute("CREATE INDEX t_id ON t (id);");                 // numeric default: B+Tree
            s.execute("CREATE INDEX t_h ON t USING HASH (h);");
            s.execute("CREATE INDEX t_name ON t (name);");             // VARCHAR default: hash
            QueryPlanner planner = new QueryPlanner(Catalog.open(new Transaction(db.bufferPool())));

            assertEquals(new ScanPlan.IndexRangeScan("t", "t_id", 10L, true, 20L, false), plan(planner, "id >= 10 AND id < 20"));
            assertEquals(new ScanPlan.IndexRangeScan("t", "t_id", 5L, false, null, false), plan(planner, "5 < id"));
            assertEquals(new ScanPlan.IndexRangeScan("t", "t_id", 12L, true, 15L, true),
                plan(planner, "id >= 10 AND id <= 15 AND id >= 12 AND name = name"), "tightest bounds win");
            assertEquals(new ScanPlan.IndexScan("t", "t_h", 3L), plan(planner, "id > 1 AND h = 3"), "equality beats a range");
            assertEquals(new ScanPlan.FullScan("t"), plan(planner, "h > 3"), "a hash index can't serve a range");
            assertEquals(new ScanPlan.FullScan("t"), plan(planner, "id > 3 OR id < 1"), "OR isn't decomposed");
            assertEquals(new ScanPlan.FullScan("t"), plan(planner, "name = 5"), "type mismatch must not use the index");
        });
    }

    public void testRangeQueriesMatchFullScans() throws Exception {
        withDb(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE ix (id INTEGER, v BIGINT);");
            s.execute("CREATE TABLE plain (id INTEGER, v BIGINT);");
            s.execute("CREATE INDEX ix_id ON ix USING BTREE (id);");
            Random rnd = new Random(5);
            s.execute("BEGIN;");
            for (int i = 0; i < 3_000; i++) {
                int id = rnd.nextInt(1_000) - 500; // duplicates and negatives
                String row = "(" + id + ", " + i + ");";
                s.execute("INSERT INTO ix VALUES " + row);
                s.execute("INSERT INTO plain VALUES " + row);
            }
            s.execute("COMMIT;");
            s.execute("DELETE FROM ix WHERE v < 300;");
            s.execute("DELETE FROM plain WHERE v < 300;");
            s.execute("UPDATE ix SET id = 9999 WHERE v > 2900;");
            s.execute("UPDATE plain SET id = 9999 WHERE v > 2900;");

            String[] predicates = {
                "id >= -10 AND id < 25", "id > 400", "id <= -480", "id = 17", "id >= 0 AND id <= 0",
                "100 >= id AND id > 90", "id > 10 AND id < 5", "id >= 9999", "id > -1000 AND v > 1500"
            };
            for (String p : predicates) {
                assertEquals(sorted(rows(s, "SELECT * FROM plain WHERE " + p + ";")), sorted(rows(s, "SELECT * FROM ix WHERE " + p + ";")),
                    "index result must equal the full scan for: " + p);
            }
        });
    }

    public void testExplicitBTreeOnVarcharIsRejected() throws Exception {
        withDb(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (name VARCHAR);");
            assertThrows(ExecutionException.class, () -> s.execute("CREATE INDEX bad ON t USING BTREE (name);"));
        });
    }

    public void testParserAcceptsUsingClause() {
        assertEquals(new Statement.CreateIndex("i", "t", "c", Statement.IndexMethod.HASH),
            Parser.parse("CREATE INDEX i ON t USING hash (c);"));
        assertEquals(new Statement.CreateIndex("i", "t", "c", Statement.IndexMethod.BTREE),
            Parser.parse("CREATE INDEX i ON t USING BTREE (c);"));
        assertEquals(new Statement.CreateIndex("i", "t", "c"), Parser.parse("CREATE INDEX i ON t (c);"));
    }

    public void testBTreeIndexSurvivesReopen() throws Exception {
        Path tmp = Files.createTempFile("stratadb-rangeplan", ".db");
        Files.deleteIfExists(tmp);
        try {
            try (Database db = Database.open(tmp); Session s = db.newSession()) {
                s.execute("CREATE TABLE t (id INTEGER);");
                s.execute("CREATE INDEX t_id ON t (id);");
                for (int i = 0; i < 500; i++) s.execute("INSERT INTO t VALUES (" + i + ");");
            }
            try (Database db = Database.open(tmp); Session s = db.newSession()) {
                assertEquals(50, rows(s, "SELECT * FROM t WHERE id >= 100 AND id < 150;").size());
            }
            try (DiskManager raw = new DiskManager(tmp)) {
                assertEquals(com.strata.catalog.IndexInfo.Kind.BTREE,
                    Catalog.open(raw).getIndexesForTable("t").getFirst().kind(), "the index kind must persist");
            }
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    // ---- helpers ----

    private static ScanPlan plan(QueryPlanner planner, String where) {
        Statement.Select select = (Statement.Select) Parser.parse("SELECT * FROM t WHERE " + where + ";");
        return planner.planScan("t", select.where());
    }

    private static List<Row> rows(Session s, String sql) throws Exception {
        return ((ExecutionResult.Rows) s.execute(sql)).rows();
    }

    private static List<String> sorted(List<Row> rows) {
        return rows.stream().map(r -> r.values().toString()).sorted(Comparator.naturalOrder()).toList();
    }

    @FunctionalInterface
    private interface DbTest {
        void run(Database db) throws Exception;
    }

    private static void withDb(DbTest test) throws Exception {
        Path tmp = Files.createTempFile("stratadb-rangeplan", ".db");
        Files.deleteIfExists(tmp);
        try (Database db = Database.open(tmp)) {
            test.run(db);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    @SuppressWarnings("unused")
    private static Optional<String> none() {
        return Optional.empty();
    }
}
