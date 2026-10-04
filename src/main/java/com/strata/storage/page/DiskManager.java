package com.strata.storage.page;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DiskManager is the boundary between StrataDB and the filesystem. It owns exactly
 * one database file and exposes it purely in terms of pages: {@code readPage(id)},
 * {@code writePage(id, page)}, {@code allocatePage()}.
 *
 * <p>Nothing above this class (buffer pool, storage engine, indexes, ...) is allowed
 * to know that the database "file" exists — as far as they're concerned, a database
 * is just an array of fixed-size pages, addressed by integer page id. That
 * abstraction is the whole point of this class.
 *
 * <h2>Why FileChannel instead of a plain InputStream/OutputStream?</h2>
 * Classic Java I/O streams are inherently sequential: they carry a single cursor
 * position, so any random-access reader/writer has to synchronize seek()+read() as
 * one atomic unit. {@link FileChannel} instead offers <em>positional</em> I/O —
 * {@code read(buffer, position)} and {@code write(buffer, position)} — which read
 * or write at an explicit offset without disturbing (or needing) any shared cursor.
 * That maps naturally onto "give me page 7" and is also safe to call concurrently
 * from multiple threads without external locking around the position itself
 * (we still guard our own bookkeeping below with {@code synchronized}).
 *
 * <h2>Addressing scheme</h2>
 * {@code byteOffset = pageId * Page.PAGE_SIZE}. This one line of arithmetic is the
 * entire address space of the database. Every higher-level "pointer" we introduce
 * later (a record id, an index entry, a free-list link) ultimately resolves to a
 * page id, which resolves to a byte offset via this formula.
 *
 * <h2>Durability note (preview of the Recovery phase)</h2>
 * A successful {@code write()} only guarantees the OS's page cache has the data —
 * not that it has reached the physical disk. {@link FileChannel#force(boolean)}
 * asks the OS to flush ("fsync") outstanding writes to stable storage. We call it
 * after every page write for now, which is correct but slow; the Buffer Pool and
 * Write-Ahead Log phases will later replace "fsync on every write" with a much
 * smarter, batched durability strategy.
 */
public final class DiskManager implements PageStore, AutoCloseable {

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final AtomicInteger numPages;
    private final Path path;

    /**
     * Opens (creating if necessary) the database file at {@code dbFile} and
     * figures out how many pages it already contains.
     */
    public DiskManager(Path dbFile) throws IOException {
        this.path = dbFile;
        this.file = new RandomAccessFile(dbFile.toFile(), "rw");
        this.channel = file.getChannel();
        long sizeInBytes = channel.size();
        if (sizeInBytes % Page.PAGE_SIZE != 0) {
            throw new IOException(
                "Corrupt database file: size " + sizeInBytes +
                " is not a multiple of PAGE_SIZE (" + Page.PAGE_SIZE + ")");
        }
        this.numPages = new AtomicInteger((int) (sizeInBytes / Page.PAGE_SIZE));
    }

    /** The path this DiskManager was opened against — used by {@code Session} to locate this database's WAL file (Phase 11). */
    public Path getPath() {
        return path;
    }

    /** Reads the page with the given id off disk. */
    @Override
    public synchronized Page readPage(int pageId) throws IOException {
        validatePageId(pageId);
        long offset = toByteOffset(pageId);
        ByteBuffer buffer = ByteBuffer.allocate(Page.PAGE_SIZE);
        readFully(buffer, offset);
        return new Page(buffer.array());
    }

    /** Overwrites the page with the given id on disk, then flushes it to stable storage. */
    @Override
    public synchronized void writePage(int pageId, Page page) throws IOException {
        validatePageId(pageId);
        long offset = toByteOffset(pageId);
        ByteBuffer buffer = ByteBuffer.wrap(page.getData());
        writeFully(buffer, offset);
        channel.force(false); // fsync data (not necessarily file metadata) — see class javadoc
    }

    /**
     * Grows the file by exactly one page, writes it as all-zero, and returns its
     * new page id. This is the *only* way page ids come into existence — there is
     * no "delete a page" yet (that arrives with the free-space/free-list logic in
     * the storage engine phase).
     */
    @Override
    public synchronized int allocatePage() throws IOException {
        int newPageId = numPages.getAndIncrement();
        writePage(newPageId, new Page());
        return newPageId;
    }

    /** Total number of pages currently in the file. */
    @Override
    public int getNumPages() {
        return numPages.get();
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
        file.close();
    }

    // ---- internals ----

    private static long toByteOffset(int pageId) {
        return (long) pageId * Page.PAGE_SIZE;
    }

    private void validatePageId(int pageId) {
        if (pageId < 0 || pageId >= numPages.get()) {
            throw new IllegalArgumentException(
                "Invalid pageId " + pageId + " (file currently has " + numPages.get() + " pages)");
        }
    }

    /** Reads until the buffer is full or EOF is hit; any unread tail is left as zero bytes. */
    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, position);
            if (bytesRead == -1) {
                break; // EOF: rest of buffer stays zero-initialized
            }
            position += bytesRead;
        }
    }

    /** Writes the whole buffer, looping in case the OS performs a short write. */
    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int bytesWritten = channel.write(buffer, position);
            position += bytesWritten;
        }
    }
}
