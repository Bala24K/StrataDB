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

    /** Total number of pages currently in the store. */
    int getNumPages();
}
