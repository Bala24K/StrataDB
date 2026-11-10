package com.strata.recovery;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.executor.Row;
import com.strata.lock.LockManager;
import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;
import com.strata.transaction.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * Randomized crash testing against a model.
 *
 * <p>A random workload of multi-statement transactions (inserts, updates, deletes, some rolled
 * back) runs against a table with a hash index, while a {@link FaultyStore} under the buffer
 * pool makes the N-th write to the data file fail — optionally tearing that page first (half new
 * bytes, half old) — and every write after it fail too, as if the process had died. The test then
 * abandons all in-memory state without flushing, reopens the file through normal recovery, and
 * checks the result against a model of acknowledged commits, via both a full scan and the index.
 *
 * <p>Faults are injected only into the data file, never the WAL, so any failure surfacing from
 * {@code COMMIT} happened <em>after</em> the commit record was fsynced; that transaction must
 * therefore be present after recovery. A failure before COMMIT means it must be absent.
 *
 * <p>What this does not cover: losing data the OS had acknowledged but not yet persisted (power
 * loss), which needs fault injection below the file system. See {@code ProcessKillTest} for a
 * real {@code kill -9}.
 */
public class CrashInjectionTest {

    private static final String PAD = "p".repeat(300); // ~12 rows per page: evictions and page growth happen quickly

    public void testRandomWorkloadsSurviveInjectedWriteFailures() throws Exception {
        java.util.EnumMap<Phase, Integer> crashes = new java.util.EnumMap<>(Phase.class);
        for (long seed = 1; seed <= 40; seed++) {
            crashes.merge(runOne(seed), 1, Integer::sum);
        }
        // Coverage, not correctness: the seeds must actually crash in every interesting phase.
        for (Phase phase : List.of(Phase.STATEMENT, Phase.COMMIT, Phase.CHECKPOINT)) {
            assertTrue(crashes.getOrDefault(phase, 0) > 0, "no seed crashed during " + phase + ": " + crashes);
        }
    }

    /** Where the injected failure surfaced. */
    enum Phase { NONE, STATEMENT, COMMIT, CHECKPOINT }

    private static Phase runOne(long seed) throws Exception {
        Random rnd = new Random(seed);
        Path db = Files.createTempFile("stratadb-crash", ".db");
        Files.deleteIfExists(db);
        Path wal = Path.of(db + ".wal");
        try {
            // Alternate index structures so both are crash-tested.
            String method = seed % 2 == 0 ? "HASH" : "BTREE";
            try (Database setup = Database.open(db);
                 Session s = setup.newSession()) {
                s.execute("CREATE TABLE kv (id INTEGER, val BIGINT, pad VARCHAR);");
                s.execute("CREATE INDEX kv_id ON kv USING " + method + " (id);");
            }

            TreeMap<Integer, Long> committed = new TreeMap<>();
            Phase crashedIn = Phase.NONE;
            // Each seed targets one phase: the fault is armed only while that phase runs, so
            // coverage of every phase doesn't depend on how many pages a given change writes.
            Phase target = Phase.values()[1 + (int) (seed % 3)];

            DiskManager dm = new DiskManager(db);
            WriteAheadLog log = Session.prepareWal(dm);
            FaultyStore faulty = new FaultyStore(dm, 1 + rnd.nextInt(3), rnd.nextBoolean());
            BufferPool pool = new BufferPool(faulty, 4); // tiny: forces eviction write-back mid-transaction
            Session s = Session.open(dm, pool, new LockManager(1_000), log);
            int nextId = 0;

            workload:
            for (int step = 0; step < 300; step++) {
                TreeMap<Integer, Long> pending = new TreeMap<>(committed);
                try {
                    faulty.armed = target == Phase.STATEMENT;
                    s.execute("BEGIN;");
                    int ops = 1 + rnd.nextInt(5);
                    for (int i = 0; i < ops; i++) {
                        int kind = pending.isEmpty() ? 0 : rnd.nextInt(3);
                        if (kind == 0) {
                            int id = nextId++;
                            long val = rnd.nextLong();
                            s.execute("INSERT INTO kv VALUES (" + id + ", " + val + ", '" + PAD + "');");
                            pending.put(id, val);
                        } else {
                            int id = randomKey(pending, rnd);
                            if (kind == 1) {
                                long val = rnd.nextLong();
                                s.execute("UPDATE kv SET val = " + val + " WHERE id = " + id + ";");
                                pending.put(id, val);
                            } else {
                                s.execute("DELETE FROM kv WHERE id = " + id + ";");
                                pending.remove(id);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    if (!faulty.failed) throw e; // a genuine bug, not our injected crash
                    crashedIn = Phase.STATEMENT;
                    break workload; // crashed mid-transaction: nothing of it may survive
                }
                if (rnd.nextInt(5) == 0) {
                    s.execute("ROLLBACK;");
                    continue;
                }
                if (step % 10 == 9) {
                    faulty.armed = target == Phase.CHECKPOINT;
                    try {
                        // Data pages reach the file only via eviction and checkpoints, so crash
                        // inside checkpoint flushes too. A failed checkpoint must not truncate the log.
                        log.checkpoint(() -> {
                            pool.flushAll();
                            faulty.sync();
                        });
                    } catch (IOException | RuntimeException e) {
                        if (!faulty.failed) throw e;
                        crashedIn = Phase.CHECKPOINT;
                        s.execute("ROLLBACK;"); // the open transaction never committed
                        break;
                    }
                }
                try {
                    faulty.armed = target == Phase.COMMIT;
                    s.execute("COMMIT;");
                    committed = pending;
                } catch (IOException | RuntimeException e) {
                    if (!faulty.failed) throw e;
                    committed = pending; // the WAL record was durable before the data write failed
                    crashedIn = Phase.COMMIT;
                    break;
                }
            }

            // "kill -9": drop the buffer pool and every cached page without flushing anything.
            dm.close();
            log.close();

            try (Database reopened = Database.open(db);
                 Session check = reopened.newSession()) {
                assertEquals(committed, scanAll(check), "seed " + seed + ": full scan must match the acknowledged commits");
                for (var entry : committed.entrySet()) {
                    List<Row> viaIndex = rows(check, "SELECT * FROM kv WHERE id = " + entry.getKey() + ";");
                    assertEquals(1, viaIndex.size(), "seed " + seed + ": index lookup for id " + entry.getKey());
                    assertEquals(entry.getValue(), viaIndex.get(0).get(1));
                }
                assertEquals(0, rows(check, "SELECT * FROM kv WHERE id = " + nextId + ";").size());
                // and the recovered database must accept new work
                check.execute("INSERT INTO kv VALUES (" + (nextId + 1) + ", 1, 'after');");
            }
            return crashedIn;
        } finally {
            Files.deleteIfExists(db);
            Files.deleteIfExists(wal);
        }
    }

    private static int randomKey(TreeMap<Integer, Long> map, Random rnd) {
        List<Integer> keys = new ArrayList<>(map.keySet());
        return keys.get(rnd.nextInt(keys.size()));
    }

    private static List<Row> rows(Session s, String sql) throws IOException {
        return ((ExecutionResult.Rows) s.execute(sql)).rows();
    }

    private static TreeMap<Integer, Long> scanAll(Session s) throws IOException {
        TreeMap<Integer, Long> result = new TreeMap<>();
        for (Row row : rows(s, "SELECT * FROM kv;")) {
            Long previous = result.put((Integer) row.get(0), (Long) row.get(1));
            assertTrue(previous == null, "duplicate row for id " + row.get(0));
        }
        return result;
    }

    /**
     * Passes everything through to the real file; while {@link #armed}, the N-th write fails
     * (optionally torn) and every write after it fails too, as if the process had died.
     */
    static final class FaultyStore implements PageStore {
        private final DiskManager disk;
        private final int failOnWrite;
        private final boolean tear;
        private int writes;
        volatile boolean failed;
        volatile boolean armed;

        FaultyStore(DiskManager disk, int failOnWrite, boolean tear) {
            this.disk = disk;
            this.failOnWrite = failOnWrite;
            this.tear = tear;
        }

        @Override
        public Page readPage(int pageId) throws IOException {
            return disk.readPage(pageId);
        }

        @Override
        public synchronized void writePage(int pageId, Page page) throws IOException {
            if (failed) throw new IOException("injected crash (process already dead)");
            if (armed && ++writes == failOnWrite) {
                failed = true;
                if (tear) {
                    Page torn = disk.readPage(pageId);
                    System.arraycopy(page.getData(), 0, torn.getData(), 0, Page.PAGE_SIZE / 2);
                    disk.writePage(pageId, torn);
                }
                throw new IOException("injected crash on write #" + writes + (tear ? " (torn page)" : ""));
            }
            disk.writePage(pageId, page);
        }

        @Override
        public int allocatePage() throws IOException {
            if (failed) throw new IOException("injected crash (process already dead)");
            return disk.allocatePage();
        }

        @Override
        public int allocatePages(int count) throws IOException {
            if (failed) throw new IOException("injected crash (process already dead)");
            return disk.allocatePages(count);
        }

        @Override
        public int getNumPages() {
            return disk.getNumPages();
        }

        @Override
        public void sync() throws IOException {
            if (failed) throw new IOException("injected crash (process already dead)");
            disk.sync();
        }
    }
}
