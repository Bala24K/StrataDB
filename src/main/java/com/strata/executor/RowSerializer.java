package com.strata.executor;

import com.strata.catalog.ColumnDefinition;
import com.strata.catalog.DataType;
import com.strata.catalog.TableInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts between a schema-typed {@link Row} and the raw {@code byte[]} that
 * {@link com.strata.storage.HeapTable} actually stores. This is the piece that finally makes
 * good on the "Record Layout" concept named all the way back in the Phase 1 plan: earlier
 * phases only ever stored opaque bytes, because there was no schema yet to interpret them
 * against. Now that {@link TableInfo} exists, a "row" can mean something concrete.
 *
 * <h2>Format</h2>
 * Same sequential, self-describing approach as {@link TableInfo}'s own (de)serialization from
 * Phase 2 — for each column, in schema order:
 * <pre>
 *   isNull : boolean (1 byte)
 *   value  : only present if isNull is false, encoded per the column's DataType:
 *              INTEGER → 4-byte int
 *              BIGINT  → 8-byte long
 *              VARCHAR → length-prefixed UTF-8 string
 *              BOOLEAN → 1 byte
 * </pre>
 * The column count and order aren't stored in the record itself — they're read from
 * {@code schema} instead, supplied by the caller from the catalog. Storing them redundantly in
 * every single row would waste space for no benefit: the catalog is already the one source of
 * truth for a table's shape.
 *
 * <p>A production system would typically pack the null flags into a single bitmap (one bit per
 * column) rather than a whole byte each, for space efficiency. A byte per column is simpler to
 * read and reason about, and the difference doesn't matter at this project's scale — a
 * reasonable thing to optimize later, not now.
 */
public final class RowSerializer {

    private RowSerializer() {}

    /** Encodes {@code row} according to {@code schema}'s column order and types. */
    public static byte[] serialize(Row row, TableInfo schema) {
        List<ColumnDefinition> columns = schema.getColumns();
        List<Object> values = row.values();
        if (values.size() != columns.size()) {
            throw new IllegalArgumentException(
                "Row has " + values.size() + " value(s) but schema '" + schema.getName()
                + "' has " + columns.size() + " column(s)");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (int i = 0; i < columns.size(); i++) {
                Object value = values.get(i);
                out.writeBoolean(value == null);
                if (value != null) {
                    writeValue(out, columns.get(i).getType(), value);
                }
            }
        } catch (IOException e) {
            // ByteArrayOutputStream never actually throws IOException; this can't happen in practice.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Decodes a byte array previously produced by {@link #serialize}, against the same schema. */
    public static Row deserialize(byte[] data, TableInfo schema) {
        List<ColumnDefinition> columns = schema.getColumns();
        List<Object> values = new ArrayList<>(columns.size());

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            for (ColumnDefinition column : columns) {
                boolean isNull = in.readBoolean();
                values.add(isNull ? null : readValue(in, column.getType()));
            }
        } catch (IOException e) {
            // ByteArrayInputStream never actually throws IOException; this can't happen in practice.
            throw new UncheckedIOException(e);
        }
        return new Row(values);
    }

    private static void writeValue(DataOutputStream out, DataType type, Object value) throws IOException {
        switch (type) {
            case INTEGER -> out.writeInt((Integer) value);
            case BIGINT -> out.writeLong((Long) value);
            case VARCHAR -> out.writeUTF((String) value);
            case BOOLEAN -> out.writeBoolean((Boolean) value);
        }
    }

    private static Object readValue(DataInputStream in, DataType type) throws IOException {
        return switch (type) {
            case INTEGER -> in.readInt();
            case BIGINT -> in.readLong();
            case VARCHAR -> in.readUTF();
            case BOOLEAN -> in.readBoolean();
        };
    }
}
