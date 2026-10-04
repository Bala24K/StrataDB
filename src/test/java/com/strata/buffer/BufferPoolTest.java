package com.strata.buffer;

import static com.strata.testutil.Assert.*;

import com.strata.storage.page.DiskManager;
import com.strata.storage.page.Page;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BufferPoolTest {

    public void testRejectsNonPositiveCapacity() throws IOException {
        withTempFile((tmp, dm) ->
            assertThrows(IllegalArgumentException.class, () -> new BufferPool(dm, 0)));
    }

    public void testReadPageReturnsUnderlyingContentOnACacheMiss() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            Page page = new Page();
            page.putInt(0, 42);
            dm.writePage(pageId, page);

            BufferPool pool = new BufferPool(dm, 8);
            assertEquals(42, pool.readPage(pageId).getInt(0));
        });
    }

    public void testReadPageCachesSoItDoesNotSeeLaterDirectChangesToTheUnderlyingStore() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 8);

            pool.readPage(pageId); // populate the cache

            // mutate the underlying store DIRECTLY, bypassing the pool entirely
            Page mutated = new Page();
            mutated.putInt(0, 999);
            dm.writePage(pageId, mutated);

            // the pool must still return its cached (stale) copy — proof that it's actually caching
            assertEquals(0, pool.readPage(pageId).getInt(0),
                "a cached page must not reflect changes made directly to the underlying store");
        });
    }

    public void testWritePageDoesNotImmediatelyReachTheUnderlyingStore() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 8);

            Page modified = new Page();
            modified.putInt(0, 999);
            pool.writePage(pageId, modified);

            assertEquals(0, dm.readPage(pageId).getInt(0), "a write through the pool must not reach disk before a flush or eviction");
        });
    }

    public void testFlushAllWritesDirtyPagesThrough() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 8);
            Page modified = new Page();
            modified.putInt(0, 999);
            pool.writePage(pageId, modified);

            pool.flushAll();

            assertEquals(999, dm.readPage(pageId).getInt(0), "flushAll must write every dirty page through to the real store");
        });
    }

    public void testEvictionFlushesADirtyPageAutomatically() throws IOException {
        withTempFile((tmp, dm) -> {
            int p1 = dm.allocatePage();
            int p2 = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 1); // capacity 1: the second write evicts the first

            Page page1 = new Page();
            page1.putInt(0, 111);
            pool.writePage(p1, page1);

            Page page2 = new Page();
            page2.putInt(0, 222);
            pool.writePage(p2, page2); // must evict p1's frame, flushing it first

            assertEquals(111, dm.readPage(p1).getInt(0),
                "an evicted dirty page must have been flushed automatically, not silently lost");
        });
    }

    public void testLeastRecentlyUsedPageIsEvictedNotMostRecentlyUsed() throws IOException {
        withTempFile((tmp, dm) -> {
            int a = dm.allocatePage();
            int b = dm.allocatePage();
            int c = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 2); // room for exactly two pages

            pool.readPage(a); // cache: [a]
            pool.readPage(b); // cache: [a, b]
            pool.readPage(a); // touch a again -> a is now more recently used than b
            pool.readPage(c); // must evict b (the least recently used), not a

            // mutate both a and b directly on the underlying store
            Page newA = new Page();
            newA.putInt(0, 1);
            dm.writePage(a, newA);
            Page newB = new Page();
            newB.putInt(0, 2);
            dm.writePage(b, newB);

            // a should still be cached (stale, pre-mutation value) since it was NOT evicted
            assertEquals(0, pool.readPage(a).getInt(0), "recently-used page 'a' must have survived eviction");
            // b should have been evicted, so reading it now re-fetches the fresh mutated value
            assertEquals(2, pool.readPage(b).getInt(0), "least-recently-used page 'b' must have been evicted and re-fetched");
        });
    }

    public void testAllocatePageGoesThroughToTheUnderlyingStoreImmediately() throws IOException {
        withTempFile((tmp, dm) -> {
            BufferPool pool = new BufferPool(dm, 8);
            int before = dm.getNumPages();

            pool.allocatePage();

            assertEquals(before + 1, dm.getNumPages(), "allocation must grow the real store immediately, not be deferred");
        });
    }

    public void testGetNumPagesDelegatesToTheUnderlyingStore() throws IOException {
        withTempFile((tmp, dm) -> {
            dm.allocatePage();
            dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 8);
            assertEquals(dm.getNumPages(), pool.getNumPages());
        });
    }

    // ---- regression tests for a real aliasing bug found while building Phase 9 ----
    // See the Phase 9 docs: mutating a Page object handed back from readPage() used to
    // corrupt the pool's own cached copy, since no defensive copy was made.

    public void testMutatingAPageReturnedByReadDoesNotCorruptTheCache() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 8);

            Page firstRead = pool.readPage(pageId);
            firstRead.putInt(0, 12345); // mutate the object the caller was handed, in place

            Page secondRead = pool.readPage(pageId);
            assertEquals(0, secondRead.getInt(0),
                "mutating a page returned by readPage() must not corrupt what the pool has cached");
        });
    }

    public void testMutatingAPageAfterWritingItDoesNotCorruptTheCache() throws IOException {
        withTempFile((tmp, dm) -> {
            int pageId = dm.allocatePage();
            BufferPool pool = new BufferPool(dm, 8);

            Page page = new Page();
            page.putInt(0, 1);
            pool.writePage(pageId, page);

            page.putInt(0, 999); // the caller keeps mutating their own object after writing it

            assertEquals(1, pool.readPage(pageId).getInt(0),
                "writePage must store its own copy — later mutations to the caller's object must not leak in");
        });
    }

    // ---- helper ----

    @FunctionalInterface
    private interface DiskManagerTest {
        void run(Path tmp, DiskManager diskManager) throws IOException;
    }

    private static void withTempFile(DiskManagerTest test) throws IOException {
        Path tmp = Files.createTempFile("stratadb-bufferpool-test", ".db");
        Files.deleteIfExists(tmp);
        try (DiskManager diskManager = new DiskManager(tmp)) {
            test.run(tmp, diskManager);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
