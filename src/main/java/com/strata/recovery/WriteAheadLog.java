package com.strata.recovery;

import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;
import com.strata.storage.page.PageStores;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * A <b>write-ahead log</b>: before any transaction's changes are allowed to reach the real
 * database file, a durable record of exactly what that transaction wrote is appended — and
 * fsynced — to this separate log file first. If the process crashes after a transaction commits
 * but before all of its pages have actually been written through the buffer pool to disk (the
 * honest gap both Phase 7 and Phase 9 flagged), {@link #recover} replays this log on the next
 * {@code open()} and finishes the job.
 *
 * <h2>Why this is enough: redo-only recovery</h2>
 * A full ARIES-style recovery scheme needs both <b>redo</b> (finish applying committed changes
 * that didn't fully make it to disk) and <b>undo</b> (roll back changes from a transaction that
 * was still in progress, if a "steal" policy let some of its dirty pages reach disk early).
 * StrataDB only needs the first half. Phase 9 established a <b>NO-STEAL</b> buffer pool policy:
 * an in-progress transaction's writes live entirely in {@code Transaction}'s own private
 * overlay and are never visible to the buffer pool — let alone disk — until that transaction's
 * own {@code commit()}. That means nothing uncommitted can ever reach the real file in the
 * first place, so there is never anything to undo. Every record this log ever writes represents
 * an already-committed transaction, which is why this format doesn't need a separate "commit
 * marker" distinguishing committed from in-progress work — a record existing in the log at all
 * *means* committed.
 *
 * <h2>Record format</h2>
 * Sequential, length-prefixed, checksummed records, appended one after another:
 * <pre>
 *   recordLength : int    (byte length of the payload that follows)
 *   payload      : txnSeq (long), numPages (int), then for each page: pageId (int) + PAGE_SIZE bytes
 *   checksum     : long   (CRC32 of the payload, for detecting a torn or corrupted record)
 * </pre>
 * The length prefix and checksum together are what let {@link #recover} safely stop at a record
 * that was only partially written when the process died mid-append, instead of misreading
 * garbage as valid data.
 *
 * <h2>A known limitation: no checkpointing</h2>
 * The log is only ever cleared at {@link #recover}, i.e. the next time the database is opened —
 * not during normal operation. A very long-running session logs every single committed
 * transaction (including every autocommit statement) for its entire lifetime, so the log file
 * grows without bound. A production system periodically <b>checkpoints</b>: flush every dirty
 * page, then safely truncate the log up to that point. Real, valuable future work — not
 * required for this log to be correct, just for it to stay small.
 */
public final class WriteAheadLog implements AutoCloseable {

    /** Smallest valid payload: txnSeq (8) + numPages (4). */
    private static final int MIN_PAYLOAD = Long.BYTES + Integer.BYTES;

    private final RandomAccessFile file;
    private final FileChannel channel;
    private long nextTxnSeq = 1;

    /** Set once a commit has failed in a way that leaves memory and the log out of step; see {@link #commit}. */
    private volatile Throwable failure;

    private WriteAheadLog(RandomAccessFile file, FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    /** Opens (creating if necessary) the log file at {@code path}, ready to append further records. */
    public static WriteAheadLog open(Path path) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
        return new WriteAheadLog(file, file.getChannel());
    }

    /**
     * Durably logs {@code pageWrites} as one committed transaction record — appended and
     * fsynced before this method returns. Callers must call this <em>before</em> applying any
     * of the same writes to the real store; that ordering is the entire meaning of
     * "write-ahead". Prefer {@link #commit}, which also applies the writes in log order.
     *
     * <p>If the append itself fails, the partial bytes are truncated away (so a later append
     * can't land behind a torn record that recovery would stop at) and the log refuses all
     * further commits — see {@link #commit}.
     */
    public synchronized long logCommit(Map<Integer, Page> pageWrites) throws IOException {
        requireHealthy();
        long txnSeq = nextTxnSeq++;
        byte[] frame = frame(encodePayload(txnSeq, pageWrites));

        long start = channel.size();
        try {
            long position = start;
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
            channel.force(true); // the actual write-ahead guarantee: durable before anything is applied
        } catch (IOException | RuntimeException e) {
            failure = e;
            try {
                channel.truncate(start);
                channel.force(true);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return txnSeq;
    }

    /**
     * Commits one transaction: logs {@code pageWrites} durably, then applies them to
     * {@code target}, as a single critical section. Holding one lock across both steps means
     * the order records appear in the log is exactly the order they were applied in memory, so
     * replaying the log reproduces the live state even if two transactions touched the same
     * page. (Separate critical sections allowed log order A,B but apply order B,A.)
     *
     * <p><b>Fail-stop.</b> If applying fails after the record is durable, the commit has
     * happened as far as the log is concerned (recovery will redo it), but memory now holds a
     * half-applied transaction. Rather than let other transactions build on that, this log
     * refuses every further commit with {@link IllegalStateException} until the database is
     * reopened, at which point recovery finishes the job from the log.
     */
    public synchronized void commit(Map<Integer, Page> pageWrites, PageStore target) throws IOException {
        logCommit(pageWrites);
        try {
            PageStores.applyAll(target, pageWrites);
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw new IOException("Commit was logged durably but could not be applied in memory; "
                + "it will be completed by recovery when the database is reopened. "
                + "No further commits are accepted until then.", e);
        }
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

    private static byte[] encodePayload(long txnSeq, Map<Integer, Page> pageWrites) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(txnSeq);
            out.writeInt(pageWrites.size());
            for (Map.Entry<Integer, Page> entry : pageWrites.entrySet()) {
                out.writeInt(entry.getKey());
                out.write(entry.getValue().getData());
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] frame(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + Integer.BYTES + Long.BYTES);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(payload.length);
            out.write(payload);
            out.writeLong(checksum(payload));
        }
        return bytes.toByteArray();
    }

    private static long checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return crc.getValue();
    }

    // ---- recovery ----

    /**
     * Replays every complete, valid record in the log at {@code path} into {@code target}, then
     * deletes the log — its contents are now durably reflected in {@code target}, so there's
     * nothing left worth keeping. A no-op if the file doesn't exist or is empty (a clean prior
     * shutdown already cleared it). Must run before anything else touches {@code target}.
     *
     * <h3>Torn tail vs. corruption</h3>
     * Records are appended one at a time and each is fsynced before the next begins, so a crash
     * can only ever damage the <em>last</em> record. Anything wrong with the final record (short,
     * implausible length, bad checksum) is therefore a torn append and is safely ignored: that
     * transaction was never acknowledged. A bad record <em>followed by more data</em> can't be
     * explained by a crash; it means the file is corrupt, and silently skipping it would drop
     * acknowledged commits after it. Recovery throws {@link IOException} instead, leaving the log
     * in place for inspection.
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
                long recordStart = position;
                ByteBuffer lengthBuf = ByteBuffer.allocate(Integer.BYTES);
                if (readFully(channel, lengthBuf, position) < Integer.BYTES) {
                    break; // torn length prefix at the very end of the log — stop, ignore it
                }
                position += Integer.BYTES;
                int payloadLength = lengthBuf.getInt(0);

                if (payloadLength < MIN_PAYLOAD || (payloadLength - MIN_PAYLOAD) % (Integer.BYTES + Page.PAGE_SIZE) != 0) {
                    // Not a length any real record could have. At the tail, that's a torn length
                    // prefix; anywhere else, corruption.
                    if (position + Math.max(payloadLength, 0) + Long.BYTES >= size || restIsZeros(channel, recordStart, size)) {
                        break;
                    }
                    throw corrupt(path, recordStart, "implausible record length " + payloadLength);
                }
                if (position + payloadLength + Long.BYTES > size) {
                    break; // torn payload or checksum — the process died mid-append of this record
                }

                ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLength);
                readFully(channel, payloadBuf, position);
                position += payloadLength;

                ByteBuffer checksumBuf = ByteBuffer.allocate(Long.BYTES);
                readFully(channel, checksumBuf, position);
                position += Long.BYTES;

                byte[] payload = payloadBuf.array();
                if (checksum(payload) != checksumBuf.getLong(0)) {
                    if (position == size || restIsZeros(channel, position, size)) {
                        break; // the final record was torn mid-write: never acknowledged, ignore it
                    }
                    throw corrupt(path, recordStart, "checksum mismatch with " + (size - position) + " bytes of log after it");
                }

                applyPayload(payload, target, path, recordStart);
            }
        }
        Files.deleteIfExists(path);
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
            + "); refusing to recover rather than silently dropping committed transactions after it");
    }

    private static void applyPayload(byte[] payload, PageStore target, Path path, long recordStart) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.readLong(); // txnSeq — not needed by recovery itself; kept for future diagnostics
            int numPages = in.readInt();
            if (payload.length != MIN_PAYLOAD + numPages * (Integer.BYTES + Page.PAGE_SIZE)) {
                throw corrupt(path, recordStart, "page count " + numPages + " doesn't match record length " + payload.length);
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
