package com.strata.recovery;

import static com.strata.testutil.Assert.*;

import com.strata.buffer.BufferPool;
import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;
import com.strata.transaction.Transaction;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class WriteAheadLogTest {

    // ---- basic logging + replay ----

    public void testRecoverAppliesLoggedWritesToTheTarget() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int pageId = target.allocatePage();

                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    Page page = new Page();
                    page.putInt(0, 12345);
                    Map<Integer, Page> writes = new LinkedHashMap<>();
                    writes.put(pageId, page);
                    wal.logCommit(writes);
                }
                // deliberately never applied `writes` to `target` directly — only recovery should do that

                WriteAheadLog.recover(walPath, target);

                assertEquals(12345, target.readPage(pageId).getInt(0), "recovery must replay the logged write");
            }
        });
    }

    public void testRecoverGrowsTheTargetForNewlyAllocatedPages() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int before = target.getNumPages();
                int newPageId = before; // the next page id the WAL record claims to have allocated

                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    Page page = new Page();
                    page.putInt(0, 999);
                    Map<Integer, Page> writes = new LinkedHashMap<>();
                    writes.put(newPageId, page);
                    wal.logCommit(writes);
                }

                WriteAheadLog.recover(walPath, target);

                assertEquals(before + 1, target.getNumPages(), "recovery must grow the file for a page that didn't exist yet");
                assertEquals(999, target.readPage(newPageId).getInt(0));
            }
        });
    }

    public void testRecoverOnMissingFileIsANoOp() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                Files.deleteIfExists(walPath);
                WriteAheadLog.recover(walPath, target); // must not throw
            }
        });
    }

    public void testRecoverClearsTheLogFile() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int pageId = target.allocatePage();
                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    Map<Integer, Page> writes = new LinkedHashMap<>();
                    writes.put(pageId, new Page());
                    wal.logCommit(writes);
                }

                WriteAheadLog.recover(walPath, target);

                assertFalse(Files.exists(walPath), "a successfully recovered log must be deleted");
            }
        });
    }

    public void testMultipleCommittedRecordsAllReplay() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p1 = target.allocatePage();
                int p2 = target.allocatePage();

                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    Page page1 = new Page();
                    page1.putInt(0, 1);
                    Map<Integer, Page> write1 = new LinkedHashMap<>();
                    write1.put(p1, page1);
                    wal.logCommit(write1);

                    Page page2 = new Page();
                    page2.putInt(0, 2);
                    Map<Integer, Page> write2 = new LinkedHashMap<>();
                    write2.put(p2, page2);
                    wal.logCommit(write2);
                }

                WriteAheadLog.recover(walPath, target);

                assertEquals(1, target.readPage(p1).getInt(0));
                assertEquals(2, target.readPage(p2).getInt(0));
            }
        });
    }

    // ---- crash-safety: torn or corrupt trailing records must not break recovery ----

    public void testRecoverIgnoresATornTrailingRecord() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p1 = target.allocatePage();

                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    Page page1 = new Page();
                    page1.putInt(0, 111);
                    Map<Integer, Page> write1 = new LinkedHashMap<>();
                    write1.put(p1, page1);
                    wal.logCommit(write1); // one complete, valid record
                }

                // simulate a crash mid-append of a second record: a few stray bytes with no
                // matching complete payload/checksum
                try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
                    raf.seek(raf.length());
                    raf.write(new byte[]{1, 2, 3, 4, 5}); // looks like the start of a length prefix, nothing more
                }

                WriteAheadLog.recover(walPath, target);

                assertEquals(111, target.readPage(p1).getInt(0), "the complete first record must still be replayed");
            }
        });
    }

    public void testRecoverStopsAtACorruptChecksum() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p1 = target.allocatePage();
                int p2 = target.allocatePage();

                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    Page page1 = new Page();
                    page1.putInt(0, 111);
                    Map<Integer, Page> write1 = new LinkedHashMap<>();
                    write1.put(p1, page1);
                    wal.logCommit(write1); // valid record, should survive

                    Page page2 = new Page();
                    page2.putInt(0, 222);
                    Map<Integer, Page> write2 = new LinkedHashMap<>();
                    write2.put(p2, page2);
                    wal.logCommit(write2); // this one gets corrupted below
                }

                // flip a byte somewhere in the middle of the file to corrupt the second record's checksum
                try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
                    long corruptAt = raf.length() - 10; // well inside the second record's page data
                    raf.seek(corruptAt);
                    int original = raf.read();
                    raf.seek(corruptAt);
                    raf.write(original ^ 0xFF);
                }

                WriteAheadLog.recover(walPath, target);

                assertEquals(111, target.readPage(p1).getInt(0), "the valid first record must still be applied");
                assertEquals(0, target.readPage(p2).getInt(0), "the corrupted second record must be discarded, not misapplied");
            }
        });
    }

    // ---- the actual point of this phase: recovering from a simulated crash ----

    public void testRecoveryFinishesACommitThatNeverReachedDiskBeforeTheCrash() throws IOException {
        withPaths((dbPath, walPath) -> {
            int pageId;

            // "before the crash": commit a transaction through a Transaction+BufferPool, but
            // deliberately never call bufferPool.flushAll() — exactly the gap Phase 9's docs
            // named: the WAL commit succeeds and is durable, but the buffer pool's own write to
            // the real DiskManager is still only sitting in memory when we "crash".
            try (DiskManager diskManager = new DiskManager(dbPath)) {
                pageId = diskManager.allocatePage();

                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    BufferPool bufferPool = new BufferPool(diskManager, 8);
                    Transaction txn = new Transaction(bufferPool, wal);

                    Page page = txn.readPage(pageId);
                    page.putInt(0, 424242);
                    txn.writePage(pageId, page);

                    txn.commit(); // logs to WAL (durable) and applies to bufferPool (NOT yet to real disk)
                    // no bufferPool.flushAll() here — this is the simulated crash point
                }

                // confirm the crash scenario is real: the raw file does NOT have the write yet
                assertEquals(0, diskManager.readPage(pageId).getInt(0),
                    "sanity check: without flushing the buffer pool, the real file must not have the write yet");
            }

            // "after the crash": a fresh DiskManager on the same file, as if the process restarted.
            try (DiskManager freshDiskManager = new DiskManager(dbPath)) {
                WriteAheadLog.recover(walPath, freshDiskManager);

                assertEquals(424242, freshDiskManager.readPage(pageId).getInt(0),
                    "recovery must finish applying the committed transaction that never reached disk before the crash");
            }
        });
    }

    // ---- helpers ----

    @FunctionalInterface
    private interface PathsTest {
        void run(Path dbPath, Path walPath) throws IOException;
    }

    private static void withPaths(PathsTest test) throws IOException {
        Path dbPath = Files.createTempFile("stratadb-wal-test", ".db");
        Path walPath = Files.createTempFile("stratadb-wal-test", ".wal");
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(walPath);
        try {
            test.run(dbPath, walPath);
        } finally {
            Files.deleteIfExists(dbPath);
            Files.deleteIfExists(walPath);
        }
    }
}
