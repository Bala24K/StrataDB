package com.strata.storage;

import static com.strata.testutil.Assert.*;

import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.metrics.Metrics;
import com.strata.storage.page.Page;
import com.strata.transaction.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/** In-place record updates and page compaction. */
public class InPlaceUpdateTest {

    public void testShrinkingAndSameSizeUpdatesKeepTheSlot() {
        SlottedPage p = SlottedPage.format(new Page());
        int slot = p.insertRecord(new byte[] {1, 2, 3, 4});
        assertTrue(p.updateRecord(slot, new byte[] {9, 9}));
        assertArrayEquals(new byte[] {9, 9}, p.getRecord(slot));
        assertTrue(p.updateRecord(slot, new byte[] {5, 5}));
        assertArrayEquals(new byte[] {5, 5}, p.getRecord(slot));
    }

    public void testGrowingUpdateUsesFreeSpaceThenCompactionThenGivesUp() {
        SlottedPage p = SlottedPage.format(new Page());
        int a = p.insertRecord(new byte[1000]);
        int b = p.insertRecord(new byte[1000]);
        int c = p.insertRecord(new byte[1000]);
        p.deleteRecord(b); // a 1000-byte hole in the middle
        byte[] grown = new byte[1900];
        Arrays.fill(grown, (byte) 7);
        // free contiguous space is ~1060 bytes: not enough alone, but enough with the hole
        assertTrue(p.updateRecord(a, grown), "compaction should reclaim the deleted record's space");
        assertArrayEquals(grown, p.getRecord(a));
        assertEquals(1000, p.getRecord(c).length, "other records survive compaction at the same slot");
        assertFalse(p.updateRecord(c, new byte[4000]), "a version that can't fit must leave the page unchanged");
        assertEquals(1000, p.getRecord(c).length);
    }

    public void testCompactionPreservesEveryLiveRecordAndSlot() {
        Random rnd = new Random(3);
        SlottedPage p = SlottedPage.format(new Page());
        byte[][] expected = new byte[40][];
        for (int i = 0; i < 40; i++) {
            byte[] r = new byte[10 + rnd.nextInt(60)];
            rnd.nextBytes(r);
            expected[i] = r;
            assertEquals(i, p.insertRecord(r));
        }
        for (int i = 0; i < 40; i += 3) {
            p.deleteRecord(i);
            expected[i] = null;
        }
        int freeBefore = p.getFreeSpaceBytes();
        p.compact();
        assertTrue(p.getFreeSpaceBytes() > freeBefore);
        assertEquals(0, p.holeBytes());
        for (int i = 0; i < 40; i++) {
            if (expected[i] == null) assertTrue(p.isDeleted(i));
            else assertArrayEquals(expected[i], p.getRecord(i));
        }
    }

    public void testUpdateOfNonIndexedColumnTouchesNoIndexPageAndKeepsResultsCorrect() throws Exception {
        Path tmp = Files.createTempFile("stratadb-inplace", ".db");
        Files.deleteIfExists(tmp);
        try (Database db = Database.open(tmp); Session s = db.newSession()) {
            s.execute("CREATE TABLE t (id INTEGER, val BIGINT, name VARCHAR);");
            s.execute("CREATE INDEX t_id ON t (id);");
            for (int i = 0; i < 50; i++) s.execute("INSERT INTO t VALUES (" + i + ", 0, 'n" + i + "');");

            Metrics before = db.metrics();
            s.execute("UPDATE t SET val = 42 WHERE id = 7;");
            Metrics d = db.metrics().minus(before);
            assertTrue(d.walBytes() < 2 * (Page.PAGE_SIZE + 64),
                "an in-place update of a non-indexed column should log ~1 heap page, not heap + index pages (logged "
                    + d.walBytes() + " bytes)");

            s.execute("UPDATE t SET id = 1007 WHERE id = 8;"); // indexed column changes
            s.execute("UPDATE t SET name = '" + "long".repeat(200) + "' WHERE id = 9;"); // grows: may move

            assertEquals(42L, rows(s, "SELECT * FROM t WHERE id = 7;").getFirst().get(1));
            assertEquals(0, rows(s, "SELECT * FROM t WHERE id = 8;").size());
            assertEquals(1, rows(s, "SELECT * FROM t WHERE id = 1007;").size());
            assertEquals(800, ((String) rows(s, "SELECT * FROM t WHERE id = 9;").getFirst().get(2)).length());
            assertEquals(50, rows(s, "SELECT * FROM t;").size());
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    private static java.util.List<com.strata.executor.Row> rows(Session s, String sql) throws Exception {
        return ((ExecutionResult.Rows) s.execute(sql)).rows();
    }
}
