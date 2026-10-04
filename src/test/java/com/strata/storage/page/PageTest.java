package com.strata.storage.page;

import static com.strata.testutil.Assert.*;

public class PageTest {

    public void testDefaultPageIsExactlyPageSizeAndZeroFilled() {
        Page page = new Page();
        assertEquals(Page.PAGE_SIZE, page.getData().length, "a fresh page must be exactly PAGE_SIZE bytes");
        for (byte b : page.getData()) {
            assertEquals((byte) 0, b, "a fresh page must be all zero bytes");
        }
    }

    public void testConstructorRejectsWrongSizedArray() {
        assertThrows(IllegalArgumentException.class,
            () -> new Page(new byte[10]),
            "wrapping a non-PAGE_SIZE array must be rejected");
    }

    public void testPutGetByte() {
        Page page = new Page();
        page.putByte(0, (byte) 7);
        page.putByte(Page.PAGE_SIZE - 1, (byte) -1);
        assertEquals((byte) 7, page.getByte(0));
        assertEquals((byte) -1, page.getByte(Page.PAGE_SIZE - 1));
    }

    public void testPutGetIntRoundTrip() {
        Page page = new Page();
        page.putInt(0, 42);
        page.putInt(100, -7);
        page.putInt(Page.PAGE_SIZE - Integer.BYTES, Integer.MAX_VALUE);
        assertEquals(42, page.getInt(0));
        assertEquals(-7, page.getInt(100));
        assertEquals(Integer.MAX_VALUE, page.getInt(Page.PAGE_SIZE - Integer.BYTES));
    }

    public void testPutGetLongRoundTrip() {
        Page page = new Page();
        page.putLong(200, 123456789012345L);
        assertEquals(123456789012345L, page.getLong(200));
    }

    public void testPutGetBytesRoundTrip() {
        Page page = new Page();
        byte[] payload = "hello, StrataDB page!".getBytes();
        page.putBytes(50, payload);
        byte[] back = page.getBytes(50, payload.length);
        assertArrayEquals(payload, back, "round-tripped byte range should match exactly");
    }

    public void testWritesDoNotBleedIntoNeighboringOffsets() {
        Page page = new Page();
        page.putInt(0, -1); // all 0xFF bytes
        page.putInt(4, 0);  // should stay all zero, immediately after
        assertEquals(0, page.getInt(4), "writing at offset 0 must not corrupt offset 4");
    }

    public void testOutOfBoundsAccessThrows() {
        Page page = new Page();
        assertThrows(IndexOutOfBoundsException.class,
            () -> page.putInt(Page.PAGE_SIZE - 2, 123),
            "an int write that overruns the page boundary must be rejected");
        assertThrows(IndexOutOfBoundsException.class,
            () -> page.getByte(-1),
            "a negative offset must be rejected");
        assertThrows(IndexOutOfBoundsException.class,
            () -> page.getByte(Page.PAGE_SIZE),
            "an offset at exactly PAGE_SIZE is one past the end and must be rejected");
    }
}
