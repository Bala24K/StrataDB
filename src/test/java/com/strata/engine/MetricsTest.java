package com.strata.engine;

import static com.strata.testutil.Assert.*;

import com.strata.metrics.Metrics;
import com.strata.transaction.Session;

import java.nio.file.Files;
import java.nio.file.Path;

/** The counters behind benchmark reports must move when (and only when) the work happens. */
public class MetricsTest {

    public void testAutocommitInsertIsCountedInEveryLayer() throws Exception {
        withDatabase(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER);");
            Metrics before = db.metrics();
            s.execute("INSERT INTO t VALUES (1);");
            Metrics d = db.metrics().minus(before);

            assertEquals(1L, d.walCommits(), "one autocommit statement = one WAL commit record");
            assertEquals(1L, d.walFsyncs());
            assertTrue(d.walBytes() > 4096, "full page images are logged");
            assertTrue(d.diskFsyncs() >= 1, "FORCE: the commit flushes dirty pages, each with an fsync");
            assertEquals(2L, d.lockAcquisitions(), "catalog lock + table lock");
            assertEquals(0L, d.lockWaits());
            assertTrue(d.bufferHits() + d.bufferMisses() > 0);
        });
    }

    public void testReadOnlyStatementWritesNothing() throws Exception {
        withDatabase(db -> {
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER);");
            s.execute("INSERT INTO t VALUES (1);");
            Metrics before = db.metrics();
            s.execute("SELECT * FROM t;");
            Metrics d = db.metrics().minus(before);
            assertEquals(0L, d.walCommits(), "a read-only transaction must not write a commit record");
            assertEquals(0L, d.diskPageWrites());
            assertEquals(0L, d.totalFsyncs());
            assertTrue(d.bufferHitRatio() > 0.99, "everything should be cached after the insert");
        });
    }

    @FunctionalInterface
    private interface DbTest {
        void run(Database db) throws Exception;
    }

    private static void withDatabase(DbTest test) throws Exception {
        Path tmp = Files.createTempFile("stratadb-metrics", ".db");
        Files.deleteIfExists(tmp);
        try (Database db = Database.open(tmp)) {
            test.run(db);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }
}
