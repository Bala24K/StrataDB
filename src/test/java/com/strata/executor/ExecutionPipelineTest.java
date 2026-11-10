package com.strata.executor;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.catalog.TableInfo;
import com.strata.engine.Database;
import com.strata.metrics.Metrics;
import com.strata.transaction.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** The streaming scan pipeline and the in-place row decoder. */
public class ExecutionPipelineTest {

    private static final TableInfo SCHEMA = new TableInfo("t", 1, List.of(
        new ColumnDefinition("i", DataType.INTEGER),
        new ColumnDefinition("b", DataType.BIGINT),
        new ColumnDefinition("s", DataType.VARCHAR),
        new ColumnDefinition("f", DataType.BOOLEAN)));

    /** The slice decoder must agree with the stream decoder on every value, including the tricky strings. */
    public void testSliceDecoderMatchesStreamDecoder() {
        String[] strings = {"", "plain ascii", "naïve café", "日本語", "nul\u0000inside", "emoji 😀", "x".repeat(3000)};
        Random rnd = new Random(1);
        for (int n = 0; n < 2_000; n++) {
            Object[] values = {
                rnd.nextInt(5) == 0 ? null : rnd.nextInt(),
                rnd.nextInt(5) == 0 ? null : rnd.nextLong(),
                rnd.nextInt(5) == 0 ? null : strings[rnd.nextInt(strings.length)],
                rnd.nextInt(5) == 0 ? null : rnd.nextBoolean()};
            Row row = new Row(Arrays.asList(values));
            byte[] bytes = RowSerializer.serialize(row, SCHEMA);
            byte[] page = new byte[bytes.length + 20];
            System.arraycopy(bytes, 0, page, 7, bytes.length); // decode from the middle of a larger buffer
            assertEquals(RowSerializer.deserialize(bytes, SCHEMA), RowSerializer.deserialize(page, 7, bytes.length, SCHEMA));
            assertEquals(row, RowSerializer.deserialize(page, 7, bytes.length, SCHEMA));
        }
    }

    public void testLimitWithoutOrderByStopsTheScanEarly() throws Exception {
        withDb(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER, pad VARCHAR);");
            s.execute("BEGIN;");
            for (int i = 0; i < 5_000; i++) s.execute("INSERT INTO t VALUES (" + i + ", '" + "p".repeat(50) + "');");
            s.execute("COMMIT;");

            Metrics before = db.metrics();
            assertEquals(10, rows(s, "SELECT * FROM t LIMIT 10;").size());
            long limitedReads = db.metrics().minus(before).bufferHits();

            before = db.metrics();
            assertEquals(5_000, rows(s, "SELECT * FROM t;").size());
            long fullReads = db.metrics().minus(before).bufferHits();

            assertTrue(limitedReads * 10 < fullReads,
                "LIMIT 10 should read a handful of pages, not the whole table (" + limitedReads + " vs " + fullReads + ")");
        });
    }

    public void testOrderByWithLimitStillSeesEveryRow() throws Exception {
        withDb(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER);");
            for (int i = 0; i < 200; i++) s.execute("INSERT INTO t VALUES (" + ((i * 37) % 200) + ");");
            List<Row> top = rows(s, "SELECT * FROM t ORDER BY id DESC LIMIT 3;");
            assertEquals(List.of(199, 198, 197), top.stream().map(r -> r.get(0)).toList());
        });
    }

    public void testUpdateAndDeleteOnlyTouchMatchingRows() throws Exception {
        withDb(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER, v INTEGER);");
            s.execute("CREATE INDEX t_id ON t (id);");
            for (int i = 0; i < 300; i++) s.execute("INSERT INTO t VALUES (" + i + ", 0);");
            assertEquals(new ExecutionResult.RowsUpdated(50), s.execute("UPDATE t SET v = 1 WHERE id >= 100 AND id < 150;"));
            assertEquals(new ExecutionResult.RowsDeleted(10), s.execute("DELETE FROM t WHERE id < 10;"));
            assertEquals(50, rows(s, "SELECT * FROM t WHERE v = 1;").size());
            assertEquals(290, rows(s, "SELECT * FROM t;").size());
            List<Object> ids = new ArrayList<>();
            for (Row r : rows(s, "SELECT * FROM t WHERE id < 12;")) ids.add(r.get(0));
            assertEquals(List.of(10, 11), ids);
        });
    }

    private static List<Row> rows(Session s, String sql) throws Exception {
        return ((ExecutionResult.Rows) s.execute(sql)).rows();
    }

    @FunctionalInterface
    private interface DbTest {
        void run(Database db) throws Exception;
    }

    private static void withDb(DbTest test) throws Exception {
        Path tmp = Files.createTempFile("stratadb-pipeline", ".db");
        Files.deleteIfExists(tmp);
        try (Database db = Database.open(tmp)) {
            test.run(db);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }
}
