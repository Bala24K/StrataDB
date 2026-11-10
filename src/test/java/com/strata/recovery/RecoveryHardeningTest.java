package com.strata.recovery;

import static com.strata.testutil.Assert.*;

import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** WAL hardening (audit items 7–9): torn tails vs. corruption, fail-stop on commit failure. */
public class RecoveryHardeningTest {

    public void testCorruptionBeforeTheLastRecordRefusesToRecover() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p = target.allocatePage();
                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    wal.logCommit(Map.of(p, pageWith(1)));
                    wal.logCommit(Map.of(p, pageWith(2)));
                }
                flipByte(walPath, 100); // inside the FIRST record's page image
                assertThrows(IOException.class, () -> WriteAheadLog.recover(walPath, target),
                    "a bad record followed by more log can't be a torn append; it must not be skipped silently");
                assertTrue(Files.exists(walPath), "the log must be kept for inspection");
            }
        });
    }

    public void testImplausibleLengthMidLogIsCorruptionNotATornTail() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p = target.allocatePage();
                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    wal.logCommit(Map.of(p, pageWith(1)));
                    wal.logCommit(Map.of(p, pageWith(2)));
                }
                try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
                    raf.seek(0);
                    raf.writeInt(-7); // first record's length prefix
                }
                assertThrows(IOException.class, () -> WriteAheadLog.recover(walPath, target));
            }
        });
    }

    public void testNegativeLengthInATornTailIsIgnored() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p = target.allocatePage();
                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    wal.logCommit(Map.of(p, pageWith(7)));
                }
                try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
                    raf.seek(raf.length());
                    raf.writeInt(-1); // previously reached ByteBuffer.allocate(-1) and failed to open
                }
                WriteAheadLog.recover(walPath, target);
                assertEquals(7, target.readPage(p).getInt(0));
            }
        });
    }

    public void testZeroFilledTailIsATornAppend() throws IOException {
        withPaths((dbPath, walPath) -> {
            try (DiskManager target = new DiskManager(dbPath)) {
                int p = target.allocatePage();
                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    wal.logCommit(Map.of(p, pageWith(9)));
                }
                try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
                    raf.setLength(raf.length() + 10_000); // file grew, data never arrived
                }
                WriteAheadLog.recover(walPath, target);
                assertEquals(9, target.readPage(p).getInt(0));
            }
        });
    }

    public void testFailedApplyMakesTheLogRefuseFurtherCommitsAndRecoveryCompletesIt() throws IOException {
        withPaths((dbPath, walPath) -> {
            int p;
            try (DiskManager dm = new DiskManager(dbPath)) {
                p = dm.allocatePage();
                PageStore failing = new PageStore() {
                    public Page readPage(int id) throws IOException { return dm.readPage(id); }
                    public void writePage(int id, Page page) throws IOException { throw new IOException("injected"); }
                    public int allocatePage() throws IOException { return dm.allocatePage(); }
                    public int getNumPages() { return dm.getNumPages(); }
                };
                try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
                    assertThrows(IOException.class, () -> wal.commit(Map.of(p, pageWith(5)), failing));
                    assertFalse(wal.isHealthy());
                    assertThrows(IllegalStateException.class, () -> wal.commit(Map.of(p, pageWith(6)), dm),
                        "no transaction may commit on top of a half-applied one");
                }
            }
            try (DiskManager reopened = new DiskManager(dbPath)) {
                WriteAheadLog.recover(walPath, reopened);
                assertEquals(5, reopened.readPage(p).getInt(0), "the logged commit must be completed by recovery");
            }
        });
    }

    // ---- helpers ----

    static Page pageWith(int value) {
        Page page = new Page();
        page.putInt(0, value);
        return page;
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(offset);
            int b = raf.read();
            raf.seek(offset);
            raf.write(b ^ 0xFF);
        }
    }

    @FunctionalInterface
    private interface PathsTest {
        void run(Path dbPath, Path walPath) throws IOException;
    }

    private static void withPaths(PathsTest test) throws IOException {
        Path dbPath = Files.createTempFile("stratadb-recovery", ".db");
        Path walPath = Path.of(dbPath + ".wal");
        Files.deleteIfExists(dbPath);
        try {
            test.run(dbPath, walPath);
        } finally {
            Files.deleteIfExists(dbPath);
            Files.deleteIfExists(walPath);
        }
    }
}
