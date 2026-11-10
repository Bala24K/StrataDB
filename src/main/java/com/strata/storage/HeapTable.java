package com.strata.storage;

import com.strata.storage.page.PageStore;
import com.strata.storage.page.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A table with no guaranteed record ordering — a <b>heap file</b>, in database terminology —
 * implemented as a singly linked list of {@link SlottedPage}s. Each page's header stores the
 * id of the next page in the table (see {@link SlottedPage#getNextPageId()}); the table itself
 * only needs to remember its <em>first</em> page id to be able to find every page it owns.
 *
 * <p>This is deliberately schema-free: a "record" here is just a {@code byte[]}. Nothing in
 * this class knows or cares what's inside those bytes — that's the System Catalog's job
 * (Phase 2) and the executor's job (Phase 5) to interpret. Keeping this layer schema-free is
 * what makes it independently testable and reusable underneath any future encoding scheme.
 *
 * <h2>Why a linked list of pages, instead of a separate index of "which pages does this table own"?</h2>
 * We don't have a system catalog yet (that's Phase 2) to store such an index in. Threading the
 * pages together via a "next page id" field in each page's own header means a table is fully
 * self-describing from just one number — its first page id — with no external bookkeeping
 * required. This is also exactly how classic heap files work in real engines.
 *
 * <h2>Known Phase 1 limitations (on purpose)</h2>
 * <ul>
 *   <li><b>Insert is a linear scan.</b> Finding space walks the page chain from the start
 *       until it finds room. Fine for a teaching-scale table; a later phase can add a
 *       "last known page with free space" hint or a free-space map to avoid the scan.</li>
 *   <li><b>No compaction.</b> Deleted records leave dead space in their page (see
 *       {@link SlottedPage}'s class docs) — nothing reclaims it yet.</li>
 *   <li><b>No concurrency control.</b> Concurrent inserts/deletes from multiple threads are
 *       not safe yet — that arrives with the Lock Manager (Phase 8).</li>
 * </ul>
 */
public final class HeapTable {

    private final PageStore pageStore;
    private final int firstPageId;

    private HeapTable(PageStore pageStore, int firstPageId) {
        this.pageStore = pageStore;
        this.firstPageId = firstPageId;
    }

    /** Creates a brand-new, empty table (allocates its first page). */
    public static HeapTable create(PageStore pageStore) throws IOException {
        int firstPageId = pageStore.allocatePage();
        formatPage(pageStore, firstPageId);
        return new HeapTable(pageStore, firstPageId);
    }

    /**
     * Reopens a table that already exists on disk, given the first page id it was created
     * with. This is the hook the future Catalog (Phase 2) will use: it'll persist each
     * table's name alongside exactly this one integer.
     */
    public static HeapTable open(PageStore pageStore, int firstPageId) {
        return new HeapTable(pageStore, firstPageId);
    }

    /** The page id a Catalog should remember in order to reopen this table later. */
    public int getFirstPageId() {
        return firstPageId;
    }

    /** Inserts a record and returns the {@link RecordId} that can be used to read or delete it later. */
    public RecordId insert(byte[] record) throws IOException {
        if (record.length > SlottedPage.maxRecordSize()) {
            throw new IllegalArgumentException(
                "Record of " + record.length + " bytes can never fit on a page "
                + "(max is " + SlottedPage.maxRecordSize() + " bytes) — consider Phase 6+ "
                + "overflow pages for large values, not supported yet");
        }

        int currentPageId = startPageForInsert();
        while (true) {
            Page page = pageStore.readPage(currentPageId);
            SlottedPage slotted = new SlottedPage(page);

            if (slotted.canFit(record.length)) {
                int slotIndex = slotted.insertRecord(record);
                pageStore.writePage(currentPageId, page);
                if (!slotted.hasNextPage()) {
                    rememberTail(currentPageId);
                }
                return new RecordId(currentPageId, slotIndex);
            }

            if (slotted.hasNextPage()) {
                currentPageId = slotted.getNextPageId();
                continue;
            }

            if (slotted.canFitAfterCompaction(record.length)) {
                // The tail page has enough dead space (deletes, shrinking updates) once packed.
                slotted.compact();
                int slotIndex = slotted.insertRecord(record);
                pageStore.writePage(currentPageId, page);
                rememberTail(currentPageId);
                return new RecordId(currentPageId, slotIndex);
            }

            // Out of room on every existing page — grow the table by one page and link it in.
            int newPageId = pageStore.allocatePage();
            formatPage(pageStore, newPageId);

            slotted.setNextPageId(newPageId);
            pageStore.writePage(currentPageId, page); // persist the new link

            currentPageId = newPageId; // loop once more; guaranteed to fit on an empty page
        }
    }

    /**
     * Where an insert should start looking for room: the table's last page if the shared tail
     * hint is usable, otherwise the first page.
     *
     * <p>Inserts used to always start at the first page and walk the whole chain, so loading N
     * rows cost O(N²) page reads (each a 4 KiB buffer pool copy): 660 KB allocated per row at
     * 20,000 rows. The hint makes it O(1). Space usage barely changes: pages before the tail
     * were passed over because the record didn't fit, and deleted space isn't reclaimed in
     * place anyway (tombstones).
     *
     * <p><b>Why a stale hint is safe.</b> Hints are shared by all transactions and may be set
     * by one that later rolls back, leaving the hint on a page that never became part of the
     * committed chain. Such a page is still all zeros outside that transaction's overlay, so
     * {@link SlottedPage#isFormatted()} rejects it and we fall back to the first page. A hint on
     * a real page of this chain that has since gained a successor is harmless: the walk simply
     * continues from it. A hint can't point into another table's chain because page ids are
     * never reused (there is no free-page list yet; adding one must revisit this).
     */
    private int startPageForInsert() throws IOException {
        Map<Integer, Integer> hints = pageStore.heapTailHints();
        if (hints == null) {
            return firstPageId;
        }
        Integer hint = hints.get(firstPageId);
        if (hint == null || hint == firstPageId || hint < 0 || hint >= pageStore.getNumPages()) {
            return firstPageId;
        }
        return new SlottedPage(pageStore.readPage(hint)).isFormatted() ? hint : firstPageId;
    }

    private void rememberTail(int pageId) {
        Map<Integer, Integer> hints = pageStore.heapTailHints();
        if (hints != null) {
            hints.put(firstPageId, pageId);
        }
    }

    /**
     * Replaces the record at {@code rid}. Updates in place (same {@link RecordId}) whenever the
     * new version fits on the record's current page; otherwise moves it (delete + insert) and
     * returns the new id. Callers must re-point anything that referenced the old id (indexes) only
     * when the returned id differs.
     */
    public RecordId update(RecordId rid, byte[] record) throws IOException {
        if (record.length > SlottedPage.maxRecordSize()) {
            // Checked before touching anything, so a too-large update changes nothing.
            throw new IllegalArgumentException(
                "Record of " + record.length + " bytes can never fit on a page (max is "
                + SlottedPage.maxRecordSize() + " bytes)");
        }
        Page page = pageStore.readPage(rid.getPageId());
        SlottedPage slotted = new SlottedPage(page);
        if (slotted.updateRecord(rid.getSlotIndex(), record)) {
            pageStore.writePage(rid.getPageId(), page);
            return rid;
        }
        delete(rid);
        return insert(record);
    }

    /** Reads the record at {@code rid}. */
    public byte[] read(RecordId rid) throws IOException {
        Page page = pageStore.readPage(rid.getPageId());
        return new SlottedPage(page).getRecord(rid.getSlotIndex());
    }

    /** Deletes the record at {@code rid}. Its slot stays reserved; see {@link SlottedPage} docs. */
    public void delete(RecordId rid) throws IOException {
        Page page = pageStore.readPage(rid.getPageId());
        SlottedPage slotted = new SlottedPage(page);
        slotted.deleteRecord(rid.getSlotIndex());
        pageStore.writePage(rid.getPageId(), page);
    }

    /**
     * Returns every non-deleted record in the table, walking the full page chain.
     * This is a full table scan — the only access path that exists until Phase 6 adds indexes.
     */
    public List<byte[]> scanAll() throws IOException {
        List<byte[]> results = new ArrayList<>();
        for (StoredRecord record : scanAllWithIds()) {
            results.add(record.data());
        }
        return results;
    }

    /**
     * One non-deleted record together with the {@link RecordId} it was found at. Added in
     * Phase 5: a plain {@code scanAll()} was enough for Phase 1–4's needs, but the executor's
     * {@code UPDATE} and {@code DELETE} need to know *which specific record* matched a WHERE
     * clause in order to actually delete or replace it — a bare {@code byte[]} isn't enough to
     * act on. This is a small, backward-compatible extension: {@link #scanAll()} above is now
     * implemented in terms of this method, so the page-chain walk exists in exactly one place.
     */
    public record StoredRecord(RecordId id, byte[] data) {}

    /** Like {@link #scanAll()}, but pairs every record with its {@link RecordId}. */
    public List<StoredRecord> scanAllWithIds() throws IOException {
        List<StoredRecord> results = new ArrayList<>();
        int currentPageId = firstPageId;

        while (true) {
            Page page = pageStore.readPage(currentPageId);
            SlottedPage slotted = new SlottedPage(page);

            for (int slotIndex = 0; slotIndex < slotted.getNumSlots(); slotIndex++) {
                if (!slotted.isDeleted(slotIndex)) {
                    results.add(new StoredRecord(new RecordId(currentPageId, slotIndex), slotted.getRecord(slotIndex)));
                }
            }

            if (!slotted.hasNextPage()) {
                break;
            }
            currentPageId = slotted.getNextPageId();
        }
        return results;
    }

    // ---- streaming access (no per-record copies) ----

    /**
     * Receives records in place: {@code data[offset .. offset + length)} is the record's bytes
     * inside its page's buffer, valid only during the call (copy anything you keep).
     */
    @FunctionalInterface
    public interface RecordVisitor {
        /** @return false to stop the scan early */
        boolean visit(RecordId rid, byte[] data, int offset, int length) throws IOException;
    }

    /**
     * Visits every live record, page by page, without copying records out of their pages and
     * without materializing the table. Unlike {@link #scanAllWithIds()}, which builds a list of
     * every record (a {@code byte[]} copy plus a wrapper object each) before the caller can
     * filter anything, this lets the caller filter, decode, and stop early (e.g. at a LIMIT).
     */
    public void scan(RecordVisitor visitor) throws IOException {
        int currentPageId = firstPageId;
        while (true) {
            Page page = pageStore.readPage(currentPageId);
            SlottedPage slotted = new SlottedPage(page);
            byte[] data = page.getData();
            for (int slot = 0; slot < slotted.getNumSlots(); slot++) {
                int length = slotted.recordLength(slot);
                if (length >= 0 && !visitor.visit(new RecordId(currentPageId, slot), data, slotted.recordOffset(slot), length)) {
                    return;
                }
            }
            if (!slotted.hasNextPage()) {
                return;
            }
            currentPageId = slotted.getNextPageId();
        }
    }

    /**
     * Visits the records at {@code rids}, in the given order. Consecutive ids on the same page
     * share one page read; index scans return ids in key order, which for clustered data means
     * long same-page runs, so a 100-row range costs a couple of page reads instead of 100.
     */
    public void visit(List<RecordId> rids, RecordVisitor visitor) throws IOException {
        int cachedPageId = -1;
        Page page = null;
        SlottedPage slotted = null;
        for (RecordId rid : rids) {
            if (rid.getPageId() != cachedPageId) {
                page = pageStore.readPage(rid.getPageId());
                slotted = new SlottedPage(page);
                cachedPageId = rid.getPageId();
            }
            int length = slotted.recordLength(rid.getSlotIndex());
            if (length < 0) {
                throw new java.util.NoSuchElementException("Slot " + rid.getSlotIndex() + " has been deleted");
            }
            if (!visitor.visit(rid, page.getData(), slotted.recordOffset(rid.getSlotIndex()), length)) {
                return;
            }
        }
    }

    private static void formatPage(PageStore pageStore, int pageId) throws IOException {
        Page page = pageStore.readPage(pageId); // freshly allocated, all-zero
        SlottedPage.format(page);
        pageStore.writePage(pageId, page);
    }
}
