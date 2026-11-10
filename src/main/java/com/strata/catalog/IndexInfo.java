package com.strata.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Everything the catalog remembers about one index: its name, which table and column it covers,
 * and the two integers ({@code directoryFirstPageId}, {@code numBuckets}) that fully describe
 * how to reopen the underlying {@code com.strata.index.hash.HashIndex} — exactly the two values
 * that class's own docs named as what a future catalog integration would need to persist.
 *
 * <p>Serializes the same sequential way {@link TableInfo} does, for the same reason: this is
 * read and written as one complete unit, never accessed mid-field.
 *
 * <p>{@link Kind} is <em>not</em> part of the serialized bytes: the catalog stores each kind
 * under its own record tag, so catalogs written before B+Trees existed (hash entries only)
 * load unchanged. For a B+Tree, {@code directoryFirstPageId} is the tree's meta page and
 * {@code numBuckets} is 0.
 */
public record IndexInfo(String indexName, String tableName, String columnName, int directoryFirstPageId, int numBuckets, Kind kind) {

    /** The index structure. */
    public enum Kind { HASH, BTREE }

    /** A hash index (the only kind that existed when this record was introduced). */
    public IndexInfo(String indexName, String tableName, String columnName, int directoryFirstPageId, int numBuckets) {
        this(indexName, tableName, columnName, directoryFirstPageId, numBuckets, Kind.HASH);
    }

    public IndexInfo {
        Objects.requireNonNull(indexName, "index name");
        Objects.requireNonNull(tableName, "table name");
        Objects.requireNonNull(columnName, "column name");
        Objects.requireNonNull(kind, "kind");
    }

    public byte[] serialize() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(indexName);
            out.writeUTF(tableName);
            out.writeUTF(columnName);
            out.writeInt(directoryFirstPageId);
            out.writeInt(numBuckets);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static IndexInfo deserialize(byte[] data) {
        return deserialize(data, Kind.HASH);
    }

    /** {@code kind} comes from the catalog record's tag; see the class docs. */
    public static IndexInfo deserialize(byte[] data, Kind kind) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String indexName = in.readUTF();
            String tableName = in.readUTF();
            String columnName = in.readUTF();
            int directoryFirstPageId = in.readInt();
            int numBuckets = in.readInt();
            return new IndexInfo(indexName, tableName, columnName, directoryFirstPageId, numBuckets, kind);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
