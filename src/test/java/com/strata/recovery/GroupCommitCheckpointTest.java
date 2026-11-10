package com.strata.recovery;

import static com.strata.testutil.Assert.*;

import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.metrics.Metrics;
import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;
import com.strata.transaction.Session;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** NO-FORCE commits, group commit, checkpoints, and the v2 log format's torn-tail logic. */
public class GroupCommitCheckpointTest {

    public void testConcurrentCommitsShareFsyncs() throws Exception {
        withDb(Database.Options.defaults(), db -> {
            int threads = 8;
            int perThread = 40;
            try (Session setup = db.newSession()) {
                for (int t = 0; t < threads; t++) setup.execute("CREATE TABLE t" + t + " (id INTEGER);");
            }
            Metrics before = db.metrics();
            runThreads(threads, t -> {
                try (Session s = db.newSession()) {
                    for (int i = 0; i < perThread; i++) s.execute("INSERT INTO t" + t + " VALUES (" + i + ");");
                }
            });
            Metrics d = db.metrics().minus(before);
            assertEquals((long) threads * perThread, d.walCommits());
            assertTrue(d.walFsyncs() < d.walCommits(),
                "with 8 concurrent committers, some fsyncs must cover several commits (fsyncs=" + d.walFsyncs()
                    + ", commits=" + d.walCommits() + ")");
            try (Session check = db.newSession()) {
                for (int t = 0; t < threads; t++) {
                    assertEquals(perThread, ((ExecutionResult.Rows) check.execute("SELECT * FROM t" + t + ";")).rows().size());
                }
            }
        });
    }

    public void testAutomaticCheckpointBoundsTheLogAndKeepsData() throws Exception {
        withDb(Database.Options.defaults().withCheckpointWalBytes(64 * 1024), db -> {
            try (Session s = db.newSession()) {
                s.execute("CREATE TABLE t (id INTEGER);");
                for (int i = 0; i < 100; i++) s.execute("INSERT INTO t VALUES (" + i + ");");
                assertTrue(db.metrics().walCheckpoints() > 0, "100 commits of >4 KiB each must cross a 64 KiB threshold");
                assertTrue(db.wal().size() <= 64 * 1024 + 3 * (Page.PAGE_SIZE + 64), "the log must stay near the threshold");
                assertEquals(100, ((ExecutionResult.Rows) s.execute("SELECT * FROM t;")).rows().size());
            }
        });
    }

    public void testCleanCloseCheckpointsSoTheNextOpenHasNothingToReplay() throws Exception {
        Path tmp = tempDb();
        try {
            try (Database db = Database.open(tmp); Session s = db.newSession()) {
                s.execute("CREATE TABLE t (id INTEGER);");
                s.execute("INSERT INTO t VALUES (1);");
            }
            assertEquals(0L, Files.size(Path.of(tmp + ".wal")), "clean close must leave an empty log");
            try (DiskManager raw = new DiskManager(tmp)) {
                assertEquals(1, com.strata.catalog.Catalog.open(raw).openTable("t").scanAll().size(),
                    "after a checkpoint the data file alone holds every committed row");
            }
        } finally {
            cleanup(tmp);
        }
    }

    public void testCrashAfterCheckpointRecoversOnlyLaterCommitsFromTheLog() throws Exception {
        Path tmp = tempDb();
        try {
            Database db = Database.open(tmp);
            Session s = db.newSession();
            s.execute("CREATE TABLE t (id INTEGER);");
            s.execute("INSERT INTO t VALUES (1);");
            db.checkpoint();
            s.execute("INSERT INTO t VALUES (2);");
            assertTrue(db.wal().size() > 0);
            db.diskManager().close(); // crash: no flush, no checkpoint
            db.wal().close();

            try (Database reopened = Database.open(tmp); Session check = reopened.newSession()) {
                assertEquals(2, ((ExecutionResult.Rows) check.execute("SELECT * FROM t;")).rows().size());
            }
        } finally {
            cleanup(tmp);
        }
    }

    /** Group commit means several unacknowledged records can be torn, and not necessarily the last. */
    public void testTornRecordFollowedOnlyByUnacknowledgedRecordsIsATornTail() throws IOException {
        Path tmp = tempDb();
        Path walPath = Path.of(tmp + ".wal");
        try (DiskManager dm = new DiskManager(tmp)) {
            int p = dm.allocatePage();
            long secondStart;
            try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                wal.logCommit(Map.of(p, RecoveryHardeningTest.pageWith(1))); // durable (acknowledged)
                secondStart = wal.size();
            }
            // Two more records appended in the same group (horizon = secondStart, never fsynced),
            // then the first of them torn while the second survived.
            appendRawV2(walPath, 2, secondStart, p, 2);
            appendRawV2(walPath, 3, secondStart, p, 3);
            flipByte(walPath, secondStart + 40);

            WriteAheadLog.recover(walPath, dm);
            assertEquals(1, dm.readPage(p).getInt(0), "recovery must stop at the torn record and ignore what follows it");
        } finally {
            cleanup(tmp);
        }
    }

    public void testTornRecordBeforeALaterDurableRecordIsCorruption() throws IOException {
        Path tmp = tempDb();
        Path walPath = Path.of(tmp + ".wal");
        try (DiskManager dm = new DiskManager(tmp)) {
            int p = dm.allocatePage();
            try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                wal.logCommit(Map.of(p, RecoveryHardeningTest.pageWith(1)));
                wal.logCommit(Map.of(p, RecoveryHardeningTest.pageWith(2))); // its horizon is past record 1
            }
            flipByte(walPath, 40); // inside record 1, which record 2 proves was durable
            assertThrows(IOException.class, () -> WriteAheadLog.recover(walPath, dm));
        } finally {
            cleanup(tmp);
        }
    }

    // ---- helpers ----

    private static void appendRawV2(Path wal, long seq, long horizon, int pageId, int value) throws IOException {
        int payloadLength = 8 + 8 + 4 + 4 + Page.PAGE_SIZE;
        java.nio.ByteBuffer frame = java.nio.ByteBuffer.allocate(8 + payloadLength + 8);
        frame.putInt(WriteAheadLog.MAGIC).putInt(payloadLength);
        frame.putLong(seq).putLong(horizon).putInt(1).putInt(pageId).put(RecoveryHardeningTest.pageWith(value).getData());
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(frame.array(), 8, payloadLength);
        frame.putLong(crc.getValue());
        try (RandomAccessFile raf = new RandomAccessFile(wal.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(frame.array());
        }
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(offset);
            int b = raf.read();
            raf.seek(offset);
            raf.write(b ^ 0xFF);
        }
    }

    interface ThreadBody {
        void run(int t) throws Exception;
    }

    private static void runThreads(int threads, ThreadBody body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int idx = t;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    body.run(idx);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            all.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : all) th.join(60_000);
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
    }

    @FunctionalInterface
    interface DbTest {
        void run(Database db) throws Exception;
    }

    private static void withDb(Database.Options options, DbTest test) throws Exception {
        Path tmp = tempDb();
        try (Database db = Database.open(tmp, options)) {
            test.run(db);
        } finally {
            cleanup(tmp);
        }
    }

    private static Path tempDb() throws IOException {
        Path tmp = Files.createTempFile("stratadb-groupcommit", ".db");
        Files.deleteIfExists(tmp);
        return tmp;
    }

    private static void cleanup(Path tmp) throws IOException {
        Files.deleteIfExists(tmp);
        Files.deleteIfExists(Path.of(tmp + ".wal"));
    }
}
