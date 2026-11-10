package com.strata.index.btree;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.storage.RecordId;
import com.strata.storage.page.DiskManager;
import com.strata.transaction.Transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/** B+Tree correctness: unit cases plus a randomized model test with structural verification. */
public class BPlusTreeTest {

    /** (key, rid) entries in index order — the model the tree must agree with. */
    private record E(long key, int pid, int slot) implements Comparable<E> {
        public int compareTo(E o) {
            return BPlusTree.compare(key, pid, slot, o.key, o.pid, o.slot);
        }

        RecordId rid() {
            return new RecordId(pid, slot);
        }
    }

    public void testEmptyTree() throws IOException {
        withStore(store -> {
            BPlusTree t = BPlusTree.create(store);
            assertEquals(List.of(), t.search(5));
            assertEquals(List.of(), t.range(null, true, null, true));
            assertEquals(0L, t.verify());
            assertFalse(t.delete(5, new RecordId(1, 1)));
        });
    }

    public void testDuplicateKeysReturnEveryRecordId() throws IOException {
        withStore(store -> {
            BPlusTree t = BPlusTree.create(store);
            for (int i = 0; i < 1000; i++) t.insert(7, new RecordId(i, 0)); // spans several leaves
            t.insert(6, new RecordId(9999, 0));
            t.insert(8, new RecordId(9999, 1));
            assertEquals(1000, t.search(7).size());
            assertTrue(t.delete(7, new RecordId(500, 0)));
            assertFalse(t.delete(7, new RecordId(500, 0)), "an entry can only be deleted once");
            assertEquals(999, t.search(7).size());
            assertEquals(1001L, t.verify());
        });
    }

    public void testSequentialInsertsBuildAMultiLevelTreeWithOrderedLeaves() throws IOException {
        withStore(store -> {
            BPlusTree t = BPlusTree.create(store);
            int n = 100_000; // > 255 * 204 entries: needs three levels
            for (int i = 0; i < n; i++) t.insert(i, new RecordId(i, 0));
            assertEquals((long) n, t.verify());
            List<RecordId> mid = t.range(500L, true, 509L, true);
            assertEquals(10, mid.size());
            for (int i = 0; i < 10; i++) assertEquals(new RecordId(500 + i, 0), mid.get(i));
            assertEquals(List.of(new RecordId(n - 1, 0)), t.search(n - 1));
        });
    }

    public void testRangeBoundsInclusiveExclusiveAndOpen() throws IOException {
        withStore(store -> {
            BPlusTree t = BPlusTree.create(store);
            for (int i = 0; i < 100; i++) t.insert(i, new RecordId(i, 0));
            assertEquals(10, t.range(10L, true, 19L, true).size());
            assertEquals(8, t.range(10L, false, 19L, false).size());
            assertEquals(10, t.range(null, true, 9L, true).size());
            assertEquals(5, t.range(95L, true, null, true).size());
            assertEquals(0, t.range(50L, false, 50L, false).size());
            assertEquals(0, t.range(200L, true, null, true).size());
        });
    }

    /**
     * Random inserts and deletes (heavy key duplication, random order) against a sorted-set
     * model; after each phase, every structural invariant is verified and random point and range
     * queries must match the model exactly.
     */
    public void testRandomOperationsMatchAModel() throws IOException {
        for (long seed = 1; seed <= 3; seed++) {
            long s = seed;
            withStore(store -> {
                Random rnd = new Random(s);
                BPlusTree t = BPlusTree.create(store);
                TreeSet<E> model = new TreeSet<>();
                for (int phase = 0; phase < 4; phase++) {
                    for (int op = 0; op < 5_000; op++) {
                        if (model.isEmpty() || rnd.nextInt(10) < 7) {
                            E e = new E(rnd.nextInt(2_000) - 1_000, rnd.nextInt(5_000), rnd.nextInt(60));
                            if (model.add(e)) t.insert(e.key(), e.rid());
                        } else {
                            E victim = randomElement(model, rnd);
                            model.remove(victim);
                            assertTrue(t.delete(victim.key(), victim.rid()), "delete of an existing entry must succeed");
                        }
                    }
                    assertEquals((long) model.size(), t.verify(), "seed " + s + " phase " + phase);
                    for (int q = 0; q < 200; q++) {
                        long a = rnd.nextInt(2_200) - 1_100;
                        long b = a + rnd.nextInt(300);
                        boolean ai = rnd.nextBoolean();
                        boolean bi = rnd.nextBoolean();
                        assertEquals(expectedRange(model, a, ai, b, bi), t.range(a, ai, b, bi),
                            "seed " + s + " range " + (ai ? "[" : "(") + a + "," + b + (bi ? "]" : ")"));
                        assertEquals(expectedRange(model, a, true, a, true), t.search(a));
                    }
                }
            });
        }
    }

    public void testRolledBackInsertsLeaveTheTreeUnchanged() throws IOException {
        withStore(store -> {
            Transaction setup = new Transaction(store);
            int meta = BPlusTree.create(setup).getMetaPageId();
            for (int i = 0; i < 300; i++) BPlusTree.open(setup, meta).insert(i, new RecordId(i, 0));
            setup.commit();

            Transaction doomed = new Transaction(store);
            BPlusTree viaDoomed = BPlusTree.open(doomed, meta);
            for (int i = 300; i < 3_000; i++) viaDoomed.insert(i, new RecordId(i, 0)); // forces splits and a new root
            doomed.rollback();

            BPlusTree committed = BPlusTree.open(store, meta);
            assertEquals(300L, committed.verify());
            assertEquals(0, committed.range(300L, true, null, true).size());
        });
    }

    // ---- helpers ----

    private static List<RecordId> expectedRange(TreeSet<E> model, long a, boolean ai, long b, boolean bi) {
        List<RecordId> out = new ArrayList<>();
        for (E e : model.subSet(new E(a, Integer.MIN_VALUE, Integer.MIN_VALUE), true, new E(b, Integer.MAX_VALUE, Integer.MAX_VALUE), true)) {
            if (!ai && e.key() == a) continue;
            if (!bi && e.key() == b) continue;
            out.add(e.rid());
        }
        return out;
    }

    private static E randomElement(TreeSet<E> set, Random rnd) {
        E probe = new E(rnd.nextInt(2_000) - 1_000, rnd.nextInt(5_000), rnd.nextInt(60));
        E e = set.ceiling(probe);
        return e != null ? e : set.first();
    }

    @FunctionalInterface
    private interface StoreTest {
        void run(BufferPool store) throws IOException;
    }

    private static void withStore(StoreTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-btree", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            test.run(new BufferPool(dm, 4096));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
