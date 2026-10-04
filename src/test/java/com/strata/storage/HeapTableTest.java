package com.strata.storage;

import static com.strata.testutil.Assert.*;

import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class HeapTableTest {

    public void testCreateAllocatesExactlyOnePage() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);
            assertEquals(1, diskManager.getNumPages(), "creating an empty table must allocate exactly its first page");
            assertEquals(0, table.getFirstPageId());
        });
    }

    public void testInsertThenReadRoundTrip() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);
            byte[] record = "row one".getBytes(StandardCharsets.UTF_8);

            RecordId rid = table.insert(record);
            byte[] readBack = table.read(rid);

            assertArrayEquals(record, readBack, "record read back must match what was inserted");
        });
    }

    public void testDeleteRemovesRecordFromScan() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);
            RecordId keep = table.insert("keep me".getBytes());
            RecordId remove = table.insert("remove me".getBytes());

            table.delete(remove);

            List<byte[]> remaining = table.scanAll();
            assertEquals(1, remaining.size(), "scanAll must skip deleted records");
            assertArrayEquals("keep me".getBytes(), remaining.get(0));
            assertArrayEquals("keep me".getBytes(), table.read(keep), "the surviving record must still be readable directly");
        });
    }

    public void testManyInsertsSpillIntoAdditionalPages() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);

            // Records deliberately large enough that only a handful fit per 4KiB page,
            // so inserting many of them forces the table to allocate more pages.
            byte[] chunk = new byte[500];
            int insertedCount = 30;
            for (int i = 0; i < insertedCount; i++) {
                table.insert(chunk);
            }

            assertTrue(diskManager.getNumPages() > 1,
                "30 x 500-byte records can't fit on one 4096-byte page — the table must have grown");
            assertEquals(insertedCount, table.scanAll().size(),
                "every inserted record must still be found by a full scan across all pages");
        });
    }

    public void testScanAllReturnsOnlyLiveRecordsAcrossMultiplePages() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);
            byte[] chunk = new byte[500];

            RecordId[] rids = new RecordId[20];
            for (int i = 0; i < rids.length; i++) {
                rids[i] = table.insert(chunk);
            }
            // delete every third record, spread across whatever pages they landed on
            for (int i = 0; i < rids.length; i += 3) {
                table.delete(rids[i]);
            }

            int expectedSurvivors = rids.length - (rids.length / 3 + 1);
            assertEquals(expectedSurvivors, table.scanAll().size(),
                "scanAll must correctly skip deleted records regardless of which page they're on");
        });
    }

    public void testReopeningTableAfterRestartPreservesData() throws IOException {
        Path tmp = Files.createTempFile("stratadb-heaptable-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            int firstPageId;
            RecordId rid;
            try (DiskManager dm1 = new DiskManager(tmp)) {
                HeapTable table = HeapTable.create(dm1);
                firstPageId = table.getFirstPageId();
                rid = table.insert("survives a restart".getBytes());
            } // simulates the process exiting

            try (DiskManager dm2 = new DiskManager(tmp)) {
                HeapTable reopened = HeapTable.open(dm2, firstPageId);
                assertArrayEquals("survives a restart".getBytes(), reopened.read(rid),
                    "a table reopened with its first page id must see data from before the restart");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void testInsertingRecordLargerThanMaxSizeThrowsImmediately() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);
            byte[] tooBig = new byte[SlottedPage.maxRecordSize() + 1];

            assertThrows(IllegalArgumentException.class, () -> table.insert(tooBig),
                "an oversized record must be rejected immediately, not loop forever allocating pages");
        });
    }

    public void testScanAllWithIdsReturnsUsableRecordIds() throws IOException {
        withTempFile((tmp, diskManager) -> {
            HeapTable table = HeapTable.create(diskManager);
            table.insert("first".getBytes());
            RecordId secondId = table.insert("second".getBytes());
            table.insert("third".getBytes());

            List<HeapTable.StoredRecord> withIds = table.scanAllWithIds();
            assertEquals(3, withIds.size());

            HeapTable.StoredRecord found = withIds.stream()
                .filter(r -> r.id().equals(secondId))
                .findFirst()
                .orElseThrow();
            assertArrayEquals("second".getBytes(), found.data());

            table.delete(secondId);
            assertEquals(2, table.scanAllWithIds().size(), "deleting via a RecordId from scanAllWithIds must actually remove that record");
        });
    }

    // ---- helper ----

    @FunctionalInterface
    private interface DiskManagerTest {
        void run(Path tmp, DiskManager diskManager) throws IOException;
    }

    private static void withTempFile(DiskManagerTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-heaptable-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            test.run(tmp, diskManager);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
