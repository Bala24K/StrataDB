package com.strata.index.hash;

import com.strata.catalog.DataType;
import com.strata.storage.RecordId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Encodes and decodes one index entry — a key plus the {@link RecordId} it points at — as a
 * flat {@code byte[]}, the same sequential-stream technique used by {@code TableInfo} (Phase 2)
 * and {@code RowSerializer} (Phase 5). Package-private: this is an implementation detail of
 * {@link HashIndex}, not part of its public surface.
 *
 * <p>Unlike a table row, an index entry doesn't need to self-describe its key's type with a
 * tag byte — a single {@link HashIndex} always indexes exactly one column of one fixed
 * {@link DataType}, so every entry a given codec instance handles shares that same type.
 */
final class EntryCodec {

    private EntryCodec() {}

    record Entry(Object key, RecordId recordId) {}

    static byte[] encode(DataType keyType, Object key, RecordId rid) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeKey(out, keyType, key);
            out.writeInt(rid.getPageId());
            out.writeInt(rid.getSlotIndex());
        } catch (IOException e) {
            throw new UncheckedIOException(e); // can't actually happen for an in-memory stream
        }
        return bytes.toByteArray();
    }

    static Entry decode(DataType keyType, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            Object key = readKey(in, keyType);
            int pageId = in.readInt();
            int slotIndex = in.readInt();
            return new Entry(key, new RecordId(pageId, slotIndex));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeKey(DataOutputStream out, DataType type, Object key) throws IOException {
        switch (type) {
            // INTEGER requires an actual Integer: narrowing a Long into 4 bytes could silently
            // lose data, so that conversion is never done implicitly.
            case INTEGER -> out.writeInt((Integer) key);
            // BIGINT tolerates a boxed Integer as well as a Long: widening never loses data,
            // so accepting either is a safe, purely convenient relaxation.
            case BIGINT -> out.writeLong(((Number) key).longValue());
            case VARCHAR -> out.writeUTF((String) key);
            case BOOLEAN -> out.writeBoolean((Boolean) key);
        }
    }

    private static Object readKey(DataInputStream in, DataType type) throws IOException {
        return switch (type) {
            case INTEGER -> in.readInt();
            case BIGINT -> in.readLong();
            case VARCHAR -> in.readUTF();
            case BOOLEAN -> in.readBoolean();
        };
    }
}
