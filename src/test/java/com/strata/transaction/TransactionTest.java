package com.strata.transaction;

import static com.strata.testutil.Assert.*;

import com.strata.catalog.Catalog;
import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.storage.HeapTable;
import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TransactionTest {

    // ---- buffering and isolation ----

    public void testWritesAreInvisibleToTheUnderlyingStoreBeforeCommit() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            Transaction txn = new Transaction(dm);

            Page modified = new Page();
            modified.putInt(0, 999);
            txn.writePage(pageId, modified);

            // reading straight from the real store (bypassing the transaction) must still see the old page
            assertEquals(0, dm.readPage(pageId).getInt(0), "an uncommitted write must not be visible outside the transaction");
        });
    }

    public void testReadYourOwnWritesWithinTheSameTransaction() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            Transaction txn = new Transaction(dm);

            Page modified = new Page();
            modified.putInt(0, 999);
            txn.writePage(pageId, modified);

            assertEquals(999, txn.readPage(pageId).getInt(0), "the same transaction must see its own uncommitted write");
        });
    }

    public void testAllocatePageWithinTransactionIsInvisibleUntilCommit() throws IOException {
        withTempFile((tmp, dm) -> {
            Transaction txn = new Transaction(dm);
            int before = dm.getNumPages();

            int newPageId = txn.allocatePage();

            assertEquals(before, dm.getNumPages(), "an allocation inside a transaction must not grow the real store yet");
            assertEquals(before + 1, txn.getNumPages(), "the transaction's own view must already reflect the allocation");
            assertTrue(newPageId >= before);
        });
    }

    // ---- commit ----

    public void testCommitAppliesBufferedWritesToTheUnderlyingStore() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            Transaction txn = new Transaction(dm);
            Page modified = new Page();
            modified.putInt(0, 999);
            txn.writePage(pageId, modified);

            txn.commit();

            assertEquals(999, dm.readPage(pageId).getInt(0), "after commit, the real store must show the new value");
        });
    }

    public void testCommitAppliesNewlyAllocatedPagesToTheUnderlyingStore() throws IOException {
        withTempFile((tmp, dm) -> {
            int before = dm.getNumPages();
            Transaction txn = new Transaction(dm);
            int newPageId = txn.allocatePage();
            Page page = new Page();
            page.putInt(0, 42);
            txn.writePage(newPageId, page);

            txn.commit();

            assertEquals(before + 1, dm.getNumPages(), "commit must actually grow the real store for a new page");
            assertEquals(42, dm.readPage(newPageId).getInt(0));
        });
    }

    public void testMultiPageTransactionCommitsAllOrNothing() throws IOException {
        withTempFile((tmp, dm) -> {
            int p1 = dm.allocatePage();
            int p2 = dm.allocatePage();
            Transaction txn = new Transaction(dm);

            Page page1 = new Page();
            page1.putInt(0, 1);
            txn.writePage(p1, page1);
            Page page2 = new Page();
            page2.putInt(0, 2);
            txn.writePage(p2, page2);

            // before commit, neither change is visible
            assertEquals(0, dm.readPage(p1).getInt(0));
            assertEquals(0, dm.readPage(p2).getInt(0));

            txn.commit();

            // after commit, both are
            assertEquals(1, dm.readPage(p1).getInt(0));
            assertEquals(2, dm.readPage(p2).getInt(0));
        });
    }

    public void testCommitSucceedsEvenIfUnderlyingStoreGrewFromUnrelatedActivity() throws IOException {
        // Superseded by Phase 12: this used to be a hard failure (see Transaction's class docs
        // for why that check was removed). A transaction committing after the underlying
        // store's page count changed for an unrelated reason — another transaction growing a
        // different table, in real usage — must still succeed correctly.
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            Transaction txn = new Transaction(dm);
            Page page = txn.readPage(pageId);
            page.putInt(0, 777);
            txn.writePage(pageId, page);

            dm.allocatePage(); // simulate an unrelated transaction growing the store concurrently

            txn.commit(); // must not throw

            assertEquals(777, dm.readPage(pageId).getInt(0), "the transaction's own write must still be applied correctly");
        });
    }

    // ---- rollback ----

    public void testRollbackDiscardsBufferedWrites() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            Transaction txn = new Transaction(dm);
            Page modified = new Page();
            modified.putInt(0, 999);
            txn.writePage(pageId, modified);

            txn.rollback();

            assertEquals(0, dm.readPage(pageId).getInt(0), "a rolled-back write must never reach the real store");
        });
    }

    public void testRollbackDiscardsAllocatedPagesToo() throws IOException {
        withTempFile((tmp, dm) -> {
            int before = dm.getNumPages();
            Transaction txn = new Transaction(dm);
            txn.allocatePage();

            txn.rollback();

            assertEquals(before, dm.getNumPages(), "a rolled-back allocation must never grow the real store");
        });
    }

    // ---- misuse guards ----

    public void testOperationsAfterCommitThrow() throws IOException {
        withTempFile((tmp, dm) -> {
            Transaction txn = new Transaction(dm);
            txn.commit();
            assertThrows(IllegalStateException.class, txn::commit, "committing twice must be rejected");
            assertThrows(IllegalStateException.class, () -> txn.writePage(0, new Page()));
        });
    }

    public void testOperationsAfterRollbackThrow() throws IOException {
        withTempFile((tmp, dm) -> {
            Transaction txn = new Transaction(dm);
            txn.rollback();
            assertThrows(IllegalStateException.class, txn::rollback, "rolling back twice must be rejected");
            assertThrows(IllegalStateException.class, () -> txn.allocatePage());
        });
    }

    // ---- integration: HeapTable/Catalog through a Transaction, with zero changes to either ----

    public void testHeapTableInsertsAreAtomicThroughATransaction() throws IOException {
        withTempFile((tmp, dm) -> {
            HeapTable realTable = HeapTable.create(dm); // created directly, not inside a transaction
            int pagesBeforeTxn = dm.getNumPages();

            Transaction txn = new Transaction(dm);
            HeapTable tableInTxn = HeapTable.open(txn, realTable.getFirstPageId());
            tableInTxn.insert("row one".getBytes());
            tableInTxn.insert("row two".getBytes());

            // the real table, read directly, must still be empty — nothing committed yet
            assertTrue(realTable.scanAll().isEmpty(), "uncommitted HeapTable inserts must be invisible outside the transaction");
            assertEquals(pagesBeforeTxn, dm.getNumPages());

            txn.commit();

            assertEquals(2, realTable.scanAll().size(), "after commit, the real table must show both inserted rows");
        });
    }

    public void testHeapTableInsertsAreDiscardedOnRollback() throws IOException {
        withTempFile((tmp, dm) -> {
            HeapTable realTable = HeapTable.create(dm);

            Transaction txn = new Transaction(dm);
            HeapTable.open(txn, realTable.getFirstPageId()).insert("should vanish".getBytes());
            txn.rollback();

            assertTrue(realTable.scanAll().isEmpty(), "a rolled-back insert must never appear in the real table");
        });
    }

    public void testCatalogCreateTableIsAtomicThroughATransaction() throws IOException {
        withTempFile((tmp, dm) -> {
            Catalog.bootstrap(dm);

            Transaction txn = new Transaction(dm);
            Catalog catalogInTxn = Catalog.open(txn);
            catalogInTxn.createTable("users", List.of(new ColumnDefinition("id", DataType.INTEGER)));

            // a fresh, independent Catalog reopened directly against the real store must not see it yet
            Catalog realCatalogView = Catalog.open(dm);
            assertFalse(realCatalogView.tableExists("users"), "an uncommitted CREATE TABLE must not be visible outside the transaction");

            txn.commit();

            Catalog afterCommit = Catalog.open(dm);
            assertTrue(afterCommit.tableExists("users"), "after commit, the table must exist when reopened directly");
        });
    }

    // ---- persistence across a restart ----

    public void testCommittedChangesSurviveClosingAndReopeningTheFile() throws IOException {
        Path tmp = Files.createTempFile("stratadb-transaction-test", ".db");
        Files.deleteIfExists(tmp);
        try {
            int pageId;
            try (DiskManager dm1 = new DiskManager(tmp)) {
                pageId = dm1.allocatePage();
                Transaction txn = new Transaction(dm1);
                Page page = new Page();
                page.putInt(0, 12345);
                txn.writePage(pageId, page);
                txn.commit();
            } // simulates the process exiting

            try (DiskManager dm2 = new DiskManager(tmp)) {
                assertEquals(12345, dm2.readPage(pageId).getInt(0), "a committed write must survive a restart");
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
        Path tmp = Files.createTempFile("stratadb-transaction-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            test.run(tmp, diskManager);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
