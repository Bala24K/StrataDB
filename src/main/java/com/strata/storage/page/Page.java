package com.strata.storage.page;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A Page is a fixed-size, raw block of bytes — the smallest unit that StrataDB
 * ever reads from or writes to disk.
 *
 * <p>Every other on-disk structure (records, slot directories, catalog entries,
 * B+Tree nodes, ...) will eventually be laid out <em>inside</em> the byte array
 * of one or more pages. This class deliberately knows nothing about any of that;
 * its only job is to let callers get and put primitive values at specific byte
 * offsets, safely.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>{@link #PAGE_SIZE} is 4096 bytes (4 KiB), matching the common OS virtual
 *       memory page size and a typical filesystem block size on most modern
 *       systems. Aligning our unit of I/O with the OS's unit of I/O means a
 *       single page read/write tends to map to a single, efficient underlying
 *       operation instead of spanning (or wasting) hardware blocks.</li>
 *   <li>Multi-byte values are stored using {@link ByteBuffer}'s default
 *       big-endian byte order, matching the network/"wire" byte order
 *       convention we'll reuse later for the network module.</li>
 *   <li>All access is offset-based (random access), not stream-based —
 *       because pages are not read top-to-bottom, they're addressed by
 *       whatever structure is stored inside them (e.g. "slot 3 starts at
 *       byte 128").</li>
 * </ul>
 */
public final class Page {

    /** Size, in bytes, of every page in the database. Fixed for the life of a database file. */
    public static final int PAGE_SIZE = 4096;

    private final byte[] data;

    /** Creates a new, zero-filled page. This is what "empty disk space" looks like. */
    public Page() {
        this.data = new byte[PAGE_SIZE];
    }

    /**
     * Wraps an existing byte array as a page. The array is used directly
     * (not copied) for performance — callers should not mutate it externally
     * after handing it to a Page.
     *
     * @throws IllegalArgumentException if the array isn't exactly PAGE_SIZE bytes
     */
    public Page(byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException(
                "Page data must be exactly " + PAGE_SIZE + " bytes, got " + data.length);
        }
        this.data = data;
    }

    /** Returns the raw backing array. Used by DiskManager to read/write the whole page at once. */
    public byte[] getData() {
        return data;
    }

    /**
     * Returns an independent copy of this page — a new {@code Page} backed by a new array with
     * the same contents. Needed anywhere a page is handed out from a cache (see
     * {@code com.strata.buffer.BufferPool}): callers throughout this codebase mutate a
     * {@code Page} in place (see the constructor's note above) and expect that mutation to be
     * theirs alone until they explicitly write it back — which only holds if what they were
     * handed isn't secretly the same array some cache is still holding onto.
     */
    public Page copy() {
        return new Page(Arrays.copyOf(data, PAGE_SIZE));
    }

    // ---- single byte ----

    public byte getByte(int offset) {
        checkBounds(offset, Byte.BYTES);
        return data[offset];
    }

    public void putByte(int offset, byte value) {
        checkBounds(offset, Byte.BYTES);
        data[offset] = value;
    }

    // ---- 4-byte int ----

    public int getInt(int offset) {
        checkBounds(offset, Integer.BYTES);
        return ByteBuffer.wrap(data).getInt(offset);
    }

    public void putInt(int offset, int value) {
        checkBounds(offset, Integer.BYTES);
        ByteBuffer.wrap(data).putInt(offset, value);
    }

    // ---- 8-byte long ----

    public long getLong(int offset) {
        checkBounds(offset, Long.BYTES);
        return ByteBuffer.wrap(data).getLong(offset);
    }

    public void putLong(int offset, long value) {
        checkBounds(offset, Long.BYTES);
        ByteBuffer.wrap(data).putLong(offset, value);
    }

    // ---- arbitrary byte ranges (for records, strings, etc. in later phases) ----

    public byte[] getBytes(int offset, int length) {
        checkBounds(offset, length);
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    public void putBytes(int offset, byte[] value) {
        checkBounds(offset, value.length);
        System.arraycopy(value, 0, data, offset, value.length);
    }

    private static void checkBounds(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > PAGE_SIZE) {
            throw new IndexOutOfBoundsException(
                "Access [offset=" + offset + ", length=" + length + "] is outside a page of size " + PAGE_SIZE);
        }
    }
}
