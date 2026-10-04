package com.strata.storage;

import static com.strata.testutil.Assert.*;

import com.strata.storage.page.Page;

import java.util.NoSuchElementException;

public class SlottedPageTest {

    public void testFormatProducesAnEmptyValidPage() {
        SlottedPage page = SlottedPage.format(new Page());
        assertEquals(0, page.getNumSlots());
        assertEquals(Page.PAGE_SIZE, page.getFreeSpaceOffset());
        assertFalse(page.hasNextPage(), "a freshly formatted page must not link to a next page");
        assertEquals(Page.PAGE_SIZE - SlottedPage.HEADER_SIZE, page.getFreeSpaceBytes());
    }

    public void testInsertThenGetRoundTrip() {
        SlottedPage page = SlottedPage.format(new Page());
        byte[] record = "hello, record!".getBytes();

        int slot = page.insertRecord(record);

        assertEquals(0, slot, "first record on a page must land in slot 0");
        assertArrayEquals(record, page.getRecord(slot), "round-tripped record bytes must match exactly");
    }

    public void testMultipleInsertsGetSequentialSlotIndices() {
        SlottedPage page = SlottedPage.format(new Page());
        int slot0 = page.insertRecord("first".getBytes());
        int slot1 = page.insertRecord("second".getBytes());
        int slot2 = page.insertRecord("third".getBytes());

        assertEquals(0, slot0);
        assertEquals(1, slot1);
        assertEquals(2, slot2);
        assertEquals(3, page.getNumSlots());
        assertArrayEquals("second".getBytes(), page.getRecord(slot1));
    }

    public void testFreeSpaceShrinksAsRecordsAreInserted() {
        SlottedPage page = SlottedPage.format(new Page());
        int before = page.getFreeSpaceBytes();

        byte[] record = "0123456789".getBytes(); // 10 bytes
        page.insertRecord(record);

        int after = page.getFreeSpaceBytes();
        int expectedConsumed = record.length + SlottedPage.SLOT_SIZE;
        assertEquals(before - expectedConsumed, after,
            "free space must shrink by exactly the record bytes plus one slot directory entry");
    }

    public void testCanFitReflectsRemainingSpace() {
        SlottedPage page = SlottedPage.format(new Page());
        int spaceForOneMore = page.getFreeSpaceBytes() - SlottedPage.SLOT_SIZE;

        assertTrue(page.canFit(spaceForOneMore), "a record that exactly uses remaining space must fit");
        assertFalse(page.canFit(spaceForOneMore + 1), "a record one byte too large must not fit");
    }

    public void testDeleteTombstonesTheSlot() {
        SlottedPage page = SlottedPage.format(new Page());
        int slot = page.insertRecord("delete me".getBytes());

        page.deleteRecord(slot);

        assertTrue(page.isDeleted(slot), "slot must report itself deleted");
        assertThrows(NoSuchElementException.class, () -> page.getRecord(slot),
            "reading a deleted slot must fail");
    }

    public void testDeletingASlotTwiceThrows() {
        SlottedPage page = SlottedPage.format(new Page());
        int slot = page.insertRecord("x".getBytes());
        page.deleteRecord(slot);

        assertThrows(NoSuchElementException.class, () -> page.deleteRecord(slot),
            "deleting an already-deleted slot must fail rather than silently succeed");
    }

    public void testDeletingOneSlotDoesNotDisturbOthers() {
        SlottedPage page = SlottedPage.format(new Page());
        int slotA = page.insertRecord("A".getBytes());
        int slotB = page.insertRecord("B".getBytes());
        int slotC = page.insertRecord("C".getBytes());

        page.deleteRecord(slotB);

        assertArrayEquals("A".getBytes(), page.getRecord(slotA), "unrelated slot A must be untouched");
        assertArrayEquals("C".getBytes(), page.getRecord(slotC), "unrelated slot C must be untouched");
        assertTrue(page.isDeleted(slotB), "slot B itself must be marked deleted");
    }

    public void testAccessingOutOfRangeSlotThrows() {
        SlottedPage page = SlottedPage.format(new Page());
        page.insertRecord("only one record".getBytes());

        assertThrows(IndexOutOfBoundsException.class, () -> page.getRecord(1),
            "slot 1 doesn't exist yet — only slot 0 does");
        assertThrows(IndexOutOfBoundsException.class, () -> page.getRecord(-1),
            "a negative slot index must be rejected");
    }

    public void testInsertingPastCapacityThrows() {
        SlottedPage page = SlottedPage.format(new Page());
        byte[] big = new byte[SlottedPage.maxRecordSize()];
        page.insertRecord(big); // fills the page entirely

        assertThrows(IllegalStateException.class, () -> page.insertRecord("no room left".getBytes()),
            "a full page must reject further inserts instead of corrupting itself");
    }

    public void testNextPageIdDefaultsToNoneAndCanBeLinked() {
        SlottedPage page = SlottedPage.format(new Page());
        assertFalse(page.hasNextPage());

        page.setNextPageId(7);

        assertTrue(page.hasNextPage());
        assertEquals(7, page.getNextPageId());
    }
}
