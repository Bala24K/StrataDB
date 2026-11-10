package com.strata.recovery;

import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;
import com.strata.storage.page.PageStores;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/**
 * The <b>write-ahead log</b>: a committed transaction's page images are appended here and made
 * durable before the transaction is acknowledged; the database file itself is written lazily.
 *
 * <h2>Policy: NO-STEAL / NO-FORCE, redo-only</h2>
 * <ul>
 *   <li><b>No-steal:</b> uncommitted changes live only in {@code Transaction}'s private overlay,
 *       never in the buffer pool, so nothing uncommitted can reach the data file. Recovery
 *       therefore never needs <em>undo</em>.</li>
 *   <li><b>No-force:</b> commit makes only the <em>log</em> durable (one fsync, shared with
 *       concurrent committers, below). Dirty pages reach the data file when the buffer pool
 *       evicts them or at a {@link #checkpoint}. This is safe because a page only enters the
 *       buffer pool after its commit record is durable: the WAL rule ("log before data") holds
 *       by construction. Before this change, every commit also fsynced each dirty data page
 *       (FORCE), roughly doubling the fsyncs per commit.</li>
 *   <li><b>Redo-only, idempotent:</b> records hold full page images, so replaying a record any
 *       number of times gives the same result, and a data page torn by a crash mid-write is
 *       repaired by replay: every page written since the last checkpoint has its latest full
 *       image in the log.</li>
 * </ul>
 *
 * <h2>Group commit</h2>
 * Appending a record and fsyncing the file are separate steps. A committer appends its record,
 * then waits until the file is durable up to the end of that record. If no fsync is in
 * progress it becomes the <em>leader</em> and fsyncs everything appended so far; committers
 * that append while that fsync runs are covered by the next one. Under concurrency one fsync
 * thus acknowledges many commits ({@code commitCount() / fsyncCount()} is the average group size).
 *
 * <p><b>Why applying after the fsync is still safe.</b> A transaction applies its pages to the
 * buffer pool only after its record is durable, so two transactions could apply in a
 * different order than they were logged. That would matter only if both wrote the same page.
 * Under strict two-phase locking they can't commit concurrently: writers of a page conflict on
 * its table's (or the catalog's) exclusive lock, which the first holds until after it has applied.
 * {@link #commit} still checks this at runtime (per-page last applied sequence number) and
 * fails stop if it is ever violated, rather than silently letting memory diverge from the log.
 *
 * <h2>Checkpoints</h2>
 * {@link #checkpoint} blocks new commits, waits for in-flight ones, runs the caller's
 * "flush every dirty page and fsync the data file" action, then truncates the log to empty.
 * This is a simple blocking (sharp) checkpoint: the stall is bounded by the buffer pool size.
 * Crash before the truncation: the full log replays (idempotent). After it: the data file is
 * already durable. A checkpoint is the only time the data file is fsynced.
 *
 * <h2>Record format (v2)</h2>
 * <pre>
 *   magic    : int  0x53574C32 ("SWL2")
 *   length   : int  payload length
 *   payload  : txnSeq (long), durableHorizon (long), numPages (int), numPages x [pageId (int), PAGE_SIZE bytes]
 *   checksum : long CRC32C of the payload
 * </pre>
 * {@code durableHorizon} is how far the log was known durable when the record was appended.
 * It lets recovery tell a torn tail from corruption even though group commit means several
 * records can be in flight (and torn, in any order) at once; see {@link #recover}. Logs written
 * before this format (v1: length, txnSeq, numPages, pages, CRC32; no magic) are still recovered:
 * a v1 length can never equal the magic number.
 */
public final class WriteAheadLog implements AutoCloseable {

    static final int MAGIC = 0x53574C32;
    private static final int V2_HEADER = Integer.BYTES * 2;
    private static final int V2_MIN_PAYLOAD = Long.BYTES * 2 + Integer.BYTES;
    private static final int V1_MIN_PAYLOAD = Long.BYTES + Integer.BYTES;
    private static final int PAGE_ENTRY = Integer.BYTES + Page.PAGE_SIZE;

    private final RandomAccessFile file;
    private final FileChannel channel;

    /** Guards the append position, the durable position and the leader election below. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition durableAdvanced = lock.newCondition();
    private long appendPosition;
    private long durablePosition;
    private boolean syncInProgress;
    private long nextTxnSeq = 1;

    /** Commits hold the read side; a checkpoint takes the write side. Fair, so a checkpoint isn't starved. */
    private final ReentrantReadWriteLock checkpointLock = new ReentrantReadWriteLock(true);

    /** pageId -> sequence number of the last commit applied to it since the last checkpoint. */
    private final Map<Integer, Long> lastAppliedSeq = new ConcurrentHashMap<>();

    /** Set once a commit has failed in a way that leaves memory and the log out of step; see {@link #commit}. */
    private volatile Throwable failure;

    private volatile long autoCheckpointBytes = Long.MAX_VALUE;
    private volatile FlushAction autoCheckpointFlush;

    // Instrumentation.
    private volatile long commitsLogged;
    private volatile long bytesLogged;
    private volatile long fsyncs;
    private volatile long checkpoints;

    private WriteAheadLog(RandomAccessFile file, FileChannel channel) throws IOException {
        this.file = file;
        this.channel = channel;
        this.appendPosition = channel.size();
        this.durablePosition = appendPosition;
    }

    /** Opens (creating if necessary) the log file at {@code path}, ready to append further records. */
    public static WriteAheadLog open(Path path) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
        return new WriteAheadLog(file, file.getChannel());
    }

    // ---- commit path ----

    /**
     * Durably logs {@code pageWrites} as one committed transaction record and returns its
     * sequence number once it is durable. Does not apply anything; prefer {@link #commit}.
     */
    public long logCommit(Map<Integer, Page> pageWrites) throws IOException {
        long[] appended = append(pageWrites);
        awaitDurable(appended[1]);
        return appended[0];
    }

    /**
     * Commits one transaction: appends its record, waits until the record is durable (group
     * commit), then applies the pages to {@code target} (the buffer pool).
     *
     * <p><b>Fail-stop.</b> If applying fails after the record is durable, the commit has
     * happened as far as the log is concerned (recovery will redo it), but memory now holds a
     * half-applied transaction. Rather than let other transactions build on that, the log then
     * refuses every further commit with {@link IllegalStateException} until the database is
     * reopened, at which point recovery finishes the job from the log.
     */
    public void commit(Map<Integer, Page> pageWrites, PageStore target) throws IOException {
        checkpointLock.readLock().lock();
        try {
            long[] appended = append(pageWrites);
            long seq = appended[0];
            awaitDurable(appended[1]);
            try {
                for (Integer pageId : pageWrites.keySet()) {
                    Long previous = lastAppliedSeq.put(pageId, seq);
                    if (previous != null && previous > seq) {
                        throw new IllegalStateException("Page " + pageId + " was applied by commit " + previous
                            + " before commit " + seq + " that precedes it in the log: two transactions wrote the "
                            + "same page concurrently, which the lock protocol must prevent");
                    }
                }
                PageStores.applyAll(target, pageWrites);
            } catch (IOException | RuntimeException e) {
                failure = e;
                throw new IOException("Commit was logged durably but could not be applied in memory; "
                    + "it will be completed by recovery when the database is reopened. "
                    + "No further commits are accepted until then.", e);
            }
        } finally {
            checkpointLock.readLock().unlock();
        }
        FlushAction flush = autoCheckpointFlush;
        if (flush != null && size() > autoCheckpointBytes) {
            checkpoint(flush, autoCheckpointBytes);
        }
    }

    /**
     * After any commit that leaves the log larger than {@code thresholdBytes}, the committing
     * thread runs a checkpoint with {@code flush}. Bounds both the log's size and recovery time.
     */
    public void configureAutoCheckpoint(long thresholdBytes, FlushAction flush) {
        this.autoCheckpointBytes = thresholdBytes;
        this.autoCheckpointFlush = flush;
    }

    /** Appends one record (not yet durable). Returns {txnSeq, endPosition}. */
    private long[] append(Map<Integer, Page> pageWrites) throws IOException {
        lock.lock();
        try {
            requireHealthy();
            long seq = nextTxnSeq++;
            ByteBuffer frame = encode(seq, durablePosition, pageWrites);
            long start = appendPosition;
            try {
                long position = start;
                while (frame.hasRemaining()) {
                    position += channel.write(frame, position);
                }
                appendPosition = position;
            } catch (IOException | RuntimeException e) {
                // Remove the partial record so nothing can ever be appended behind it, then
                // refuse further commits.
                failure = e;
                try {
                    channel.truncate(start);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                durableAdvanced.signalAll();
                throw e;
            }
            commitsLogged++;
            bytesLogged += appendPosition - start;
            return new long[] {seq, appendPosition};
        } finally {
            lock.unlock();
        }
    }

    /** Blocks until the log is durable up to {@code end}, fsyncing as the group leader if nobody else is. */
    private void awaitDurable(long end) throws IOException {
        lock.lock();
        try {
            while (durablePosition < end) {
                requireHealthy();
                if (syncInProgress) {
                    durableAdvanced.awaitUninterruptibly();
                    continue;
                }
                syncInProgress = true;
                long upTo = appendPosition; // everything appended so far is covered by this fsync
                lock.unlock();
                IOException error = null;
                try {
                    channel.force(true);
                } catch (IOException e) {
                    error = e;
                }
                lock.lock();
                syncInProgress = false;
                if (error != null) {
                    failure = error;
                    durableAdvanced.signalAll();
                    throw error;
                }
                fsyncs++;
                durablePosition = Math.max(durablePosition, upTo);
                durableAdvanced.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    // ---- checkpoint ----

    /** The caller's part of a checkpoint: write every dirty page to the data file and fsync it. */
    @FunctionalInterface
    public interface FlushAction {
        void flushAndSync() throws IOException;
    }

    /**
     * Blocks new commits, waits for in-flight ones to finish applying, runs {@code flush}
     * (which must leave every committed change durable in the data file), then truncates the
     * log. See the class docs.
     */
    public void checkpoint(FlushAction flush) throws IOException {
        checkpoint(flush, -1);
    }

    /** Checkpoints only if the log is still larger than {@code minBytes} once commits are blocked. */
    private void checkpoint(FlushAction flush, long minBytes) throws IOException {
        checkpointLock.writeLock().lock();
        try {
            if (size() <= minBytes) {
                return; // another thread checkpointed first
            }
            requireHealthy();
            flush.flushAndSync();
            lock.lock();
            try {
                channel.truncate(0);
                channel.force(true);
                appendPosition = 0;
                durablePosition = 0;
                lastAppliedSeq.clear();
                checkpoints++;
            } finally {
                lock.unlock();
            }
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    /** Bytes currently in the log (since the last checkpoint). */
    public long size() {
        lock.lock();
        try {
            return appendPosition;
        } finally {
            lock.unlock();
        }
    }

    public long commitCount() {
        return commitsLogged;
    }

    public long bytesLogged() {
        return bytesLogged;
    }

    public long fsyncCount() {
        return fsyncs;
    }

    public long checkpointCount() {
        return checkpoints;
    }

    /** True until a commit fails in a way that requires a restart; see {@link #commit}. */
    public boolean isHealthy() {
        return failure == null;
    }

    private void requireHealthy() {
        Throwable f = failure;
        if (f != null) {
            throw new IllegalStateException("The write-ahead log refused a commit because an earlier commit failed ("
                + f + "); reopen the database to recover", f);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
        file.close();
    }

    // ---- encoding ----

    private static ByteBuffer encode(long seq, long durableHorizon, Map<Integer, Page> pageWrites) {
        int payloadLength = V2_MIN_PAYLOAD + pageWrites.size() * PAGE_ENTRY;
        ByteBuffer frame = ByteBuffer.allocate(V2_HEADER + payloadLength + Long.BYTES);
        frame.putInt(MAGIC).putInt(payloadLength);
        int payloadStart = frame.position();
        frame.putLong(seq).putLong(durableHorizon).putInt(pageWrites.size());
        for (Map.Entry<Integer, Page> entry : pageWrites.entrySet()) {
            frame.putInt(entry.getKey()).put(entry.getValue().getData());
        }
        CRC32C crc = new CRC32C();
        crc.update(frame.array(), payloadStart, payloadLength);
        frame.putLong(crc.getValue());
        return frame.flip();
    }

    // ---- recovery ----

    /** One parsed, checksum-valid record. */
    private record Record(int version, byte[] payload, long next) {
        long durableHorizon() {
            return version == 2 ? ByteBuffer.wrap(payload).getLong(Long.BYTES) : -1;
        }
    }

    /**
     * Replays every valid record in the log at {@code path} into {@code target}, makes
     * {@code target} durable ({@link PageStore#sync}), then deletes the log. A no-op if the file
     * doesn't exist or is empty (a clean shutdown checkpoints the log to empty). Must run before
     * anything else touches {@code target}.
     *
     * <h3>Torn tail vs. corruption</h3>
     * Only records that were never acknowledged can be damaged by a crash: those appended after
     * the last completed fsync. With group commit there can be several, torn in any order. So
     * when a record at offset X is unreadable (short, implausible length, bad checksum),
     * recovery looks for any later valid v2 record whose {@code durableHorizon} is beyond X. If
     * one exists, X had already been made durable — and acknowledged — before that record was
     * written, so this is corruption: recovery throws {@link IOException} and keeps the log for
     * inspection rather than silently dropping acknowledged commits. Otherwise X and everything
     * after it is an unacknowledged torn tail and is ignored. (A corrupted record with nothing
     * durable after it is indistinguishable from a torn tail; that's inherent to the format.)
     */
    public static void recover(Path path, PageStore target) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return;
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = file.getChannel()) {
            long size = channel.size();
            long position = 0;
            while (position < size) {
                Record record = tryParse(channel, position, size);
                if (record == null) {
                    checkTornTail(path, channel, position, size);
                    break;
                }
                applyPayload(record, target, path, position);
                position = record.next();
            }
        }
        target.sync(); // replayed pages must be durable before the log that describes them is deleted
        Files.deleteIfExists(path);
    }

    /** Throws if the unreadable record at {@code badAt} can't be explained as a torn tail. */
    private static void checkTornTail(Path path, FileChannel channel, long badAt, long size) throws IOException {
        if (restIsZeros(channel, badAt, size)) {
            return; // the file grew but the data never arrived: a torn append
        }
        boolean v1 = readInt(channel, badAt, size) != MAGIC && !hasMagicAnywhere(channel, badAt, size);
        if (v1) {
            // Legacy (v1) logs were written one fsynced record at a time, so only the last record can be torn.
            long len = readInt(channel, badAt, size);
            if (badAt + Integer.BYTES + Math.max(len, 0) + Long.BYTES >= size) {
                return;
            }
            throw corrupt(path, badAt, "unreadable record followed by more log");
        }
        // Resynchronise: find later valid v2 records by their magic number and check their horizons.
        for (long p = badAt + 1; p + V2_HEADER <= size; p++) {
            if (readInt(channel, p, size) != MAGIC) continue;
            Record later = tryParse(channel, p, size);
            if (later != null && later.durableHorizon() > badAt) {
                throw corrupt(path, badAt, "unreadable record, but a later record at byte " + p
                    + " shows the log was already durable past it");
            }
        }
    }

    private static boolean hasMagicAnywhere(FileChannel channel, long from, long size) throws IOException {
        for (long p = from; p + Integer.BYTES <= size; p++) {
            if (readInt(channel, p, size) == MAGIC) return true;
        }
        return false;
    }

    /** Parses and checksums the record at {@code p}, or returns null if it's incomplete or invalid. */
    private static Record tryParse(FileChannel channel, long p, long size) throws IOException {
        if (p + Integer.BYTES > size) return null;
        int first = readInt(channel, p, size);
        if (first == MAGIC) {
            if (p + V2_HEADER > size) return null;
            int len = readInt(channel, p + Integer.BYTES, size);
            if (len < V2_MIN_PAYLOAD || (len - V2_MIN_PAYLOAD) % PAGE_ENTRY != 0) return null;
            long checksumAt = p + V2_HEADER + len;
            if (checksumAt + Long.BYTES > size) return null;
            byte[] payload = readBytes(channel, p + V2_HEADER, len);
            CRC32C crc = new CRC32C();
            crc.update(payload);
            if (crc.getValue() != readLong(channel, checksumAt)) return null;
            return new Record(2, payload, checksumAt + Long.BYTES);
        }
        // v1: length, payload, CRC32
        int len = first;
        if (len < V1_MIN_PAYLOAD || (len - V1_MIN_PAYLOAD) % PAGE_ENTRY != 0) return null;
        long checksumAt = p + Integer.BYTES + len;
        if (checksumAt + Long.BYTES > size) return null;
        byte[] payload = readBytes(channel, p + Integer.BYTES, len);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != readLong(channel, checksumAt)) return null;
        return new Record(1, payload, checksumAt + Long.BYTES);
    }

    private static void applyPayload(Record record, PageStore target, Path path, long recordStart) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(record.payload()))) {
            in.readLong(); // txnSeq
            if (record.version() == 2) {
                in.readLong(); // durableHorizon
            }
            int numPages = in.readInt();
            int header = record.version() == 2 ? V2_MIN_PAYLOAD : V1_MIN_PAYLOAD;
            if (record.payload().length != header + numPages * PAGE_ENTRY) {
                throw corrupt(path, recordStart, "page count " + numPages + " doesn't match record length " + record.payload().length);
            }
            Map<Integer, Page> writes = new LinkedHashMap<>();
            for (int i = 0; i < numPages; i++) {
                int pageId = in.readInt();
                byte[] data = new byte[Page.PAGE_SIZE];
                in.readFully(data);
                writes.put(pageId, new Page(data));
            }
            PageStores.applyAll(target, writes);
        }
    }

    /**
     * Some filesystems can persist a file's new length before its data, so a crash mid-append
     * may leave a zero-filled tail. That's still a torn append, not corruption.
     */
    private static boolean restIsZeros(FileChannel channel, long from, long size) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        for (long pos = from; pos < size; ) {
            buf.clear();
            int n = channel.read(buf, pos);
            if (n <= 0) break;
            for (int i = 0; i < n; i++) {
                if (buf.get(i) != 0) return false;
            }
            pos += n;
        }
        return true;
    }

    private static IOException corrupt(Path path, long offset, String detail) {
        return new IOException("Write-ahead log " + path + " is corrupt at byte " + offset + " (" + detail
            + "); refusing to recover rather than silently dropping committed transactions");
    }

    private static int readInt(FileChannel channel, long p, long size) throws IOException {
        if (p + Integer.BYTES > size) return 0;
        ByteBuffer b = ByteBuffer.allocate(Integer.BYTES);
        readFully(channel, b, p);
        return b.getInt(0);
    }

    private static long readLong(FileChannel channel, long p) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(Long.BYTES);
        readFully(channel, b, p);
        return b.getLong(0);
    }

    private static byte[] readBytes(FileChannel channel, long p, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length);
        readFully(channel, b, p);
        return b.array();
    }

    private static int readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position + total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }
}
