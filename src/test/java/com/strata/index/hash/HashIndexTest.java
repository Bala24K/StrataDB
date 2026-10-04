package com.strata.index.hash;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.DataType;
import com.strata.storage.RecordId;
import com.strata.storage.page.DiskManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class HashIndexTest {

    public void testCreateAllocatesExactlyNumBucketsPages() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.INTEGER, 8);
            assertEquals(8, dm.getNumPages());
            assertEquals(8, index.getNumBuckets());
        });
    }

    public void testCreateRejectsZeroOrFewerBuckets() throws IOException {
        withTempFile((tmp, dm) ->
            assertThrows(IllegalArgumentException.class, () -> HashIndex.create(dm, DataType.INTEGER, 0)));
    }

    public void testInsertThenSearchFindsTheRecord() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.INTEGER, 8);
            RecordId rid = new RecordId(3, 1);

            index.insert(42, rid);

            assertEquals(List.of(rid), index.search(42));
        });
    }

    public void testSearchForMissingKeyReturnsEmpty() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.INTEGER, 8);
            assertTrue(index.search(999).isEmpty());
        });
    }

    public void testDuplicateKeysMapToMultipleRecordIds() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.VARCHAR, 8);
            RecordId ridA = new RecordId(1, 0);
            RecordId ridB = new RecordId(2, 0);

            index.insert("shared", ridA);
            index.insert("shared", ridB);

            List<RecordId> found = index.search("shared");
            assertEquals(2, found.size());
            assertTrue(found.contains(ridA));
            assertTrue(found.contains(ridB));
        });
    }

    public void testDeleteRemovesOnlyTheMatchingEntry() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.VARCHAR, 8);
            RecordId ridA = new RecordId(1, 0);
            RecordId ridB = new RecordId(2, 0);
            index.insert("shared", ridA);
            index.insert("shared", ridB);

            boolean removed = index.delete("shared", ridA);

            assertTrue(removed);
            assertEquals(List.of(ridB), index.search("shared"));
        });
    }

    public void testDeletingAnUnindexedEntryReturnsFalse() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.INTEGER, 8);
            assertFalse(index.delete(42, new RecordId(0, 0)));
        });
    }

    public void testDistinctKeysSharingABucketDoNotInterfere() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.INTEGER, 1); // one bucket: everything collides
            index.insert(1, new RecordId(1, 0));
            index.insert(2, new RecordId(2, 0));
            index.insert(3, new RecordId(3, 0));

            assertEquals(List.of(new RecordId(2, 0)), index.search(2));
            assertEquals(List.of(new RecordId(1, 0)), index.search(1));
            assertEquals(List.of(new RecordId(3, 0)), index.search(3));
        });
    }

    public void testBucketGrowsPastOnePageViaOverflowChaining() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.VARCHAR, 1); // one bucket forces everything into it
            int pagesBefore = dm.getNumPages();

            for (int i = 0; i < 500; i++) {
                index.insert("key" + i, new RecordId(i, 0));
            }

            assertTrue(dm.getNumPages() > pagesBefore,
                "500 entries in a single bucket must overflow past its root page into more linked pages");
            assertEquals(List.of(new RecordId(250, 0)), index.search("key250"),
                "a key stored on an overflow page must still be found by search");
        });
    }

    public void testIntegerAndLongKeysOfTheSameValueHashToTheSameBucket() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.BIGINT, 8);
            RecordId rid = new RecordId(5, 0);

            index.insert(Integer.valueOf(1234), rid); // boxed as Integer, tolerated for a BIGINT index

            assertEquals(List.of(rid), index.search(1234L), // searched as a Long
                "Integer(1234) and Long(1234) represent the same number and must be treated as the same key");
        });
    }

    public void testInsertingNullKeyThrows() throws IOException {
        withTempFile((tmp, dm) -> {
            HashIndex index = HashIndex.create(dm, DataType.INTEGER, 8);
            assertThrows(IllegalArgumentException.class, () -> index.insert(null, new RecordId(0, 0)));
        });
    }

    public void testReopeningAnExistingIndexAfterRestart() throws IOException {
        Path tmp = Files.createTempFile("stratadb-hashindex-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            int directoryFirstPageId;
            try (DiskManager dm1 = new DiskManager(tmp)) {
                HashIndex index = HashIndex.create(dm1, DataType.INTEGER, 8);
                directoryFirstPageId = index.getDirectoryFirstPageId();
                index.insert(7, new RecordId(1, 2));
            } // simulates the process exiting

            try (DiskManager dm2 = new DiskManager(tmp)) {
                HashIndex reopened = HashIndex.open(dm2, DataType.INTEGER, directoryFirstPageId, 8);
                assertEquals(List.of(new RecordId(1, 2)), reopened.search(7));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- helper ----

    @FunctionalInterface
    private interface DiskManagerTest {
        void run(Path tmp, DiskManager diskManager) throws IOException;
    }

    private static void withTempFile(DiskManagerTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-hashindex-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            test.run(tmp, diskManager);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
