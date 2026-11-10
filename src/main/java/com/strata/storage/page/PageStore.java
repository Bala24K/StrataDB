package com.strata.storage.page;

import java.io.IOException;

/**
 * The page-level contract that everything above the storage layer (a heap file, a catalog, a
 * hash index bucket) actually depends on: read a page, write a page, allocate a new page, ask
 * how many pages exist. {@link DiskManager} is the "real" implementation, backed by an actual
 * file on disk.
 *
 * <p>This interface is extracted in Phase 7 specifically so that {@code com.strata.transaction.Transaction}
 * can implement it too — presenting the exact same page-level surface, but buffering writes in
 * memory instead of touching disk, so they can be discarded (rollback) or applied all at once
 * (commit). Every class that previously depended on the concrete {@code DiskManager} type
 * ({@code HeapTable}, {@code Catalog}) now depends on this interface instead, which is what lets
 * a {@code Transaction} slot in underneath them without either of those classes needing to
 * change a single line of their own logic — see the Phase 7 docs for why that worked out.
 */
public interface PageStore {

    Page readPage(int pageId) throws IOException;

    void writePage(int pageId, Page page) throws IOException;

    /** Grows the store by one page (zero-filled) and returns its new page id. */
    int allocatePage() throws IOException;

    /**
     * Grows the store by {@code count} zero-filled pages with <em>consecutive</em> ids and returns
     * the first one. Structures that address pages arithmetically (the hash index's bucket array)
     * need this to be atomic with respect to other allocators: a loop of {@link #allocatePage()}
     * calls can interleave with another thread's allocation and leave a gap. Stores shared between
     * threads must override this atomically; this default is only correct single-threaded.
     */
    default int allocatePages(int count) throws IOException {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        int first = allocatePage();
        for (int i = 1; i < count; i++) {
            int next = allocatePage();
            if (next != first + i) {
                throw new IllegalStateException("non-contiguous allocation: expected page " + (first + i) + ", got " + next);
            }
        }
        return first;
    }

    /** Total number of pages currently in the store. */
    int getNumPages();

    /**
     * Makes every write so far durable. A no-op for stores with nothing to make durable
     * (in-memory overlays); {@code DiskManager} fsyncs its file. Since the move to a no-force
     * policy this is the only point at which the data file is fsynced: at a checkpoint, and at
     * the end of WAL recovery.
     */
    default void sync() throws IOException {
    }
}
