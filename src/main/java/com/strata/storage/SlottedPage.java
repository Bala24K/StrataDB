package com.strata.storage;

import com.strata.storage.page.Page;

import java.util.NoSuchElementException;

/**
 * Interprets a raw {@link Page} as a <b>slotted page</b>: a header, a slot directory that
 * grows forward from just after the header, and record data that grows backward from the end
 * of the page. This is the layout essentially every disk-based relational database uses to
 * pack variable-length records into fixed-size pages while keeping each record's "address"
 * (its slot index) stable even as other records on the same page are inserted or deleted.
 *
 * <pre>
 *   byte 0                                                              byte PAGE_SIZE-1
 *   ┌───────────┬────────┬────────┬────────┬─────────────┬───────┬───────┐
 *   │  HEADER   │ slot 0 │ slot 1 │ slot 2 │  free space  │ rec 1 │ rec 0 │
 *   └───────────┴────────┴────────┴────────┴─────────────┴───────┴───────┘
 *                └──── grows forward ────►               ◄─── grows backward ───┘
 * </pre>
 *
 * <h2>Why data grows from the *end* of the page, backward</h2>
 * The slot directory doesn't know in advance how many slots a page will end up with, and
 * neither does the record data know how much space it'll need. Growing the two areas toward
 * each other from opposite ends of the page means both can grow as needed, and "free space"
 * is simply whatever's left in the middle — no separate free list to maintain within a page.
 *
 * <h2>Why a slot index, not a byte offset, is a record's stable address</h2>
 * If callers referenced records by byte offset directly, deleting or shrinking one record
 * could force every other record's address to change. Instead, each slot holds the *current*
 * {@code (offset, length)} for its record, and callers only ever refer to the slot index. The
 * slot's offset can move around (e.g. during future compaction) without invalidating anyone's
 * reference — only the slot index has to stay put. A {@link RecordId} is exactly
 * {@code (pageId, slotIndex)} for this reason.
 *
 * <h2>Delete = tombstone, not reclaim (a deliberate Phase 1 simplification)</h2>
 * Deleting a record marks its slot's length as {@link #TOMBSTONE} but does <b>not</b> reclaim
 * or compact the freed bytes in the data area. This keeps every other record's slot index
 * (and therefore every existing {@link RecordId}) valid after a delete, which is important —
 * but it does mean a page can accumulate "holes" of dead space over time. Reclaiming that
 * space (compaction) is intentionally deferred; it's easy to add later as an operation that
 * rewrites a page's data area and only ever changes slot *contents*, never slot *indices* —
 * so nothing above this class needs to change when we add it.
 */
public final class SlottedPage {

    /** numSlots (4 bytes) + freeSpaceOffset (4 bytes) + nextPageId (4 bytes). */
    public static final int HEADER_SIZE = 12;

    /** Each slot directory entry: recordOffset (4 bytes) + recordLength (4 bytes). */
    public static final int SLOT_SIZE = 8;

    /** Sentinel record length meaning "this slot's record has been deleted". */
    private static final int TOMBSTONE = -1;

    /** Sentinel page id meaning "this is the last page in the table's page chain". */
    public static final int NO_NEXT_PAGE = -1;

    private static final int NUM_SLOTS_OFFSET = 0;
    private static final int FREE_SPACE_OFFSET_OFFSET = 4;
    private static final int NEXT_PAGE_ID_OFFSET = 8;

    private final Page page;

    /** Wraps a page that has already been formatted (via {@link #format}) or read back from disk. */
    public SlottedPage(Page page) {
        this.page = page;
    }

    /**
     * The largest single record this layout can ever hold, on a completely empty page.
     * Used to reject "impossible" inserts immediately instead of looping forever trying
     * (and failing) to find room on ever-more pages.
     */
    public static int maxRecordSize() {
        return Page.PAGE_SIZE - HEADER_SIZE - SLOT_SIZE;
    }

    /** Formats a fresh, all-zero page into an empty, valid slotted page and returns a view over it. */
    public static SlottedPage format(Page page) {
        SlottedPage slotted = new SlottedPage(page);
        slotted.setNumSlots(0);
        slotted.setFreeSpaceOffset(Page.PAGE_SIZE);
        slotted.setNextPageId(NO_NEXT_PAGE);
        return slotted;
    }

    public Page getPage() {
        return page;
    }

    public int getNumSlots() {
        return page.getInt(NUM_SLOTS_OFFSET);
    }

    public int getFreeSpaceOffset() {
        return page.getInt(FREE_SPACE_OFFSET_OFFSET);
    }

    public int getNextPageId() {
        return page.getInt(NEXT_PAGE_ID_OFFSET);
    }

    public boolean hasNextPage() {
        return getNextPageId() != NO_NEXT_PAGE;
    }

    /** Links this page to the next page in its table's page chain (see {@link com.strata.storage.HeapTable}). */
    public void setNextPageId(int pageId) {
        page.putInt(NEXT_PAGE_ID_OFFSET, pageId);
    }

    /** Bytes currently unused, between the end of the slot directory and the start of the record data area. */
    public int getFreeSpaceBytes() {
        int slotDirectoryEnd = HEADER_SIZE + getNumSlots() * SLOT_SIZE;
        return getFreeSpaceOffset() - slotDirectoryEnd;
    }

    /**
     * Whether a record of this length could be inserted right now. Conservatively assumes a
     * brand-new slot is needed (Phase 1 never reuses a tombstoned slot's directory entry),
     * so this is always safe even though it can under-use space that a smarter allocator
     * (a later improvement) could reclaim.
     */
    public boolean canFit(int recordLength) {
        return getFreeSpaceBytes() >= recordLength + SLOT_SIZE;
    }

    /** Writes {@code record} into this page and returns the slot index it was assigned. */
    public int insertRecord(byte[] record) {
        if (!canFit(record.length)) {
            throw new IllegalStateException(
                "Page has no room for a " + record.length + "-byte record "
                + "(only " + getFreeSpaceBytes() + " free bytes left)");
        }
        int recordOffset = getFreeSpaceOffset() - record.length;
        page.putBytes(recordOffset, record);

        int slotIndex = getNumSlots();
        writeSlot(slotIndex, recordOffset, record.length);

        setFreeSpaceOffset(recordOffset);
        setNumSlots(slotIndex + 1);
        return slotIndex;
    }

    /** Reads the record stored at {@code slotIndex}. */
    public byte[] getRecord(int slotIndex) {
        validateSlotIndex(slotIndex);
        int length = slotLength(slotIndex);
        if (length == TOMBSTONE) {
            throw new NoSuchElementException("Slot " + slotIndex + " has been deleted");
        }
        return page.getBytes(slotOffset(slotIndex), length);
    }

    /** Marks the record at {@code slotIndex} as deleted. Its slot index stays reserved (never reused). */
    public void deleteRecord(int slotIndex) {
        validateSlotIndex(slotIndex);
        if (slotLength(slotIndex) == TOMBSTONE) {
            throw new NoSuchElementException("Slot " + slotIndex + " is already deleted");
        }
        writeSlot(slotIndex, 0, TOMBSTONE);
    }

    public boolean isDeleted(int slotIndex) {
        validateSlotIndex(slotIndex);
        return slotLength(slotIndex) == TOMBSTONE;
    }

    // ---- internals: the slot directory itself ----

    private int slotHeaderOffset(int slotIndex) {
        return HEADER_SIZE + slotIndex * SLOT_SIZE;
    }

    private int slotOffset(int slotIndex) {
        return page.getInt(slotHeaderOffset(slotIndex));
    }

    private int slotLength(int slotIndex) {
        return page.getInt(slotHeaderOffset(slotIndex) + 4);
    }

    private void writeSlot(int slotIndex, int offset, int length) {
        page.putInt(slotHeaderOffset(slotIndex), offset);
        page.putInt(slotHeaderOffset(slotIndex) + 4, length);
    }

    private void validateSlotIndex(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= getNumSlots()) {
            throw new IndexOutOfBoundsException(
                "Slot " + slotIndex + " is out of range (page has " + getNumSlots() + " slots)");
        }
    }

    private void setNumSlots(int numSlots) {
        page.putInt(NUM_SLOTS_OFFSET, numSlots);
    }

    private void setFreeSpaceOffset(int offset) {
        page.putInt(FREE_SPACE_OFFSET_OFFSET, offset);
    }
}
