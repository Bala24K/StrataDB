package com.strata.storage.page;

import static com.strata.testutil.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DiskManagerTest {

    public void testAllocatePageIdsAreSequentialStartingAtZero() throws IOException {
        withTempFile(tmp -> {
            try (DiskManager dm = new DiskManager(tmp)) {
                assertEquals(0, dm.allocatePage());
                assertEquals(1, dm.allocatePage());
                assertEquals(2, dm.allocatePage());
                assertEquals(3, dm.getNumPages());
            }
        });
    }

    public void testWriteThenReadRoundTrip() throws IOException {
        withTempFile(tmp -> {
            try (DiskManager dm = new DiskManager(tmp)) {
                int pageId = dm.allocatePage();

                Page page = dm.readPage(pageId);
                page.putInt(0, 999);
                dm.writePage(pageId, page);

                Page reread = dm.readPage(pageId);
                assertEquals(999, reread.getInt(0), "value must survive a write/read round trip");
            }
        });
    }

    public void testDataSurvivesClosingAndReopeningTheFile() throws IOException {
        withTempFile(tmp -> {
            int pageId;
            try (DiskManager dm1 = new DiskManager(tmp)) {
                pageId = dm1.allocatePage();
                Page page = new Page();
                page.putLong(10, 123456789L);
                dm1.writePage(pageId, page);
            } // simulates the process exiting

            try (DiskManager dm2 = new DiskManager(tmp)) {
                assertEquals(1, dm2.getNumPages(), "reopened file must report the page count it was left with");
                Page reread = dm2.readPage(pageId);
                assertEquals(123456789L, reread.getLong(10), "data must survive a close + reopen of the file");
            }
        });
    }

    public void testPagesAreFullyIsolatedFromEachOther() throws IOException {
        withTempFile(tmp -> {
            try (DiskManager dm = new DiskManager(tmp)) {
                int p0 = dm.allocatePage();
                int p1 = dm.allocatePage();

                Page page0 = new Page();
                page0.putInt(0, 111);
                dm.writePage(p0, page0);

                Page page1 = new Page();
                page1.putInt(0, 222);
                dm.writePage(p1, page1);

                assertEquals(111, dm.readPage(p0).getInt(0), "page 0 must be unaffected by writes to page 1");
                assertEquals(222, dm.readPage(p1).getInt(0), "page 1 must be unaffected by writes to page 0");
            }
        });
    }

    public void testReadingAnInvalidPageIdThrows() throws IOException {
        withTempFile(tmp -> {
            try (DiskManager dm = new DiskManager(tmp)) {
                dm.allocatePage(); // only page 0 exists
                assertThrows(IllegalArgumentException.class,
                    () -> dm.readPage(5),
                    "reading a page id beyond the end of the file must be rejected");
                assertThrows(IllegalArgumentException.class,
                    () -> dm.readPage(-1),
                    "reading a negative page id must be rejected");
            }
        });
    }

    // ---- helper: run a check against a fresh, empty temp file, cleaning up afterward ----

    @FunctionalInterface
    private interface FileTest {
        void run(Path tmp) throws IOException;
    }

    private static void withTempFile(FileTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-test", ".db");
        Files.deleteIfExists(tmp); // DiskManager should create it fresh
        try {
            test.run(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
