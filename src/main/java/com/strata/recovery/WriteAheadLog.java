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

    private final RandomAccessFile file;
    private final FileChannel channel;
    private long nextTxnSeq = 1;

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
     * fsynced before this method returns. Callers (see {@code com.strata.transaction.Transaction})
     * must call this <em>before</em> applying any of the same writes to the real store; that
     * ordering is the entire meaning of "write-ahead."
     */
    public synchronized long logCommit(Map<Integer, Page> pageWrites) throws IOException {
        long txnSeq = nextTxnSeq++;
        byte[] payload = encodePayload(txnSeq, pageWrites);
        byte[] frame = frame(payload);

        long position = channel.size();
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
        channel.force(true); // the actual write-ahead guarantee: durable before the caller applies anything to the real store

        return txnSeq;
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
                ByteBuffer lengthBuf = ByteBuffer.allocate(Integer.BYTES);
                if (readFully(channel, lengthBuf, position) < Integer.BYTES) {
                    break; // torn length prefix at the very end of the log — stop, ignore it
                }
                position += Integer.BYTES;
                int payloadLength = lengthBuf.getInt(0);

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
                    break; // corrupt record — don't trust anything from here on either
                }

                applyPayload(payload, target);
            }
        }
        Files.deleteIfExists(path);
    }

    private static void applyPayload(byte[] payload, PageStore target) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.readLong(); // txnSeq — not needed by recovery itself; kept for future diagnostics
            int numPages = in.readInt();
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
