package com.strata.storage;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.storage.page.DiskManager;
import com.strata.transaction.Transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The shared tail-page hint must speed inserts up without ever misplacing a record. */
public class HeapTailHintTest {

    private static final byte[] WIDE = new byte[1000]; // 4 per page

    public void testInsertsStartAtTheTailInsteadOfWalkingTheChain() throws IOException {
        withPool((dm, pool) -> {
            HeapTable table = HeapTable.create(pool);
            for (int i = 0; i < 200; i++) table.insert(WIDE); // ~50 pages
            long readsBefore = pool.hitCount() + pool.missCount();
            table.insert(WIDE);
            long reads = pool.hitCount() + pool.missCount() - readsBefore;
            assertTrue(reads <= 5, "an insert should touch the tail (and maybe a new page), not walk ~50 pages (read " + reads + ")");
            assertEquals(201, table.scanAll().size());
        });
    }

    public void testHintLeftByARolledBackTransactionIsIgnored() throws IOException {
        withPool((dm, pool) -> {
            Transaction setup = new Transaction(pool);
            int first = HeapTable.create(setup).getFirstPageId();
            setup.commit();

            // Grow the table inside a transaction (moving the shared hint onto a new page), then roll back.
            Transaction doomed = new Transaction(pool);
            HeapTable viaDoomed = HeapTable.open(doomed, first);
            for (int i = 0; i < 12; i++) viaDoomed.insert(WIDE);
            int hinted = pool.heapTailHints().get(first);
            assertTrue(hinted != first, "the doomed transaction should have moved the hint to a page it allocated");
            doomed.rollback();

            // A later transaction must not insert into that never-committed page.
            Transaction later = new Transaction(pool);
            RecordId rid = HeapTable.open(later, first).insert(new byte[] {7});
            later.commit();
            assertEquals(first, rid.getPageId(), "the stale hint must be rejected, so the insert lands in the real chain");
            assertEquals(1, HeapTable.open(pool, first).scanAll().size());
        });
    }

    public void testHintsDoNotLeakAcrossTables() throws IOException {
        withPool((dm, pool) -> {
            HeapTable a = HeapTable.create(pool);
            HeapTable b = HeapTable.create(pool);
            for (int i = 0; i < 20; i++) {
                a.insert(WIDE);
                b.insert(new byte[] {(byte) i});
            }
            assertEquals(20, a.scanAll().size());
            assertEquals(20, b.scanAll().size());
            for (byte[] r : b.scanAll()) assertEquals(1, r.length);
        });
    }

    @FunctionalInterface
    private interface PoolTest {
        void run(DiskManager dm, BufferPool pool) throws IOException;
    }

    private static void withPool(PoolTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-tailhint", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager dm = new DiskManager(tmp)) {
            test.run(dm, new BufferPool(dm, 256));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
