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

    /**
     * Decodes a row directly from {@code buf[offset .. offset + length)} (typically the record's
     * bytes inside its page buffer), with no intermediate copy and no stream objects. Produces
     * exactly what {@link #deserialize(byte[], TableInfo)} would for the same bytes.
     *
     * <p>Why a second decoder: the stream-based one allocates a {@code ByteArrayInputStream} and
     * a {@code DataInputStream} per row, and scans used to copy each record out of its page
     * first. VARCHAR values use {@code writeUTF}'s format (2-byte length + modified UTF-8);
     * bytes 0x01-0x7F mean the same in modified and standard UTF-8 (NUL is never encoded as a
     * single 0x00 byte), so pure-ASCII strings are built directly and anything else falls back
     * to {@code DataInputStream.readUTF} for exact semantics.
     */
    public static Row deserialize(byte[] buf, int offset, int length, TableInfo schema) {
        List<ColumnDefinition> columns = schema.getColumns();
        Object[] values = new Object[columns.size()];
        int p = offset;
        int end = offset + length;
        for (int c = 0; c < values.length; c++) {
            if (p >= end) throw new IllegalArgumentException("Row bytes end before column " + c);
            boolean isNull = buf[p++] != 0;
            if (isNull) continue;
            switch (columns.get(c).getType()) {
                case INTEGER -> {
                    values[c] = ((buf[p] & 0xFF) << 24) | ((buf[p + 1] & 0xFF) << 16) | ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
                    p += 4;
                }
                case BIGINT -> {
                    long v = 0;
                    for (int i = 0; i < 8; i++) v = (v << 8) | (buf[p + i] & 0xFF);
                    values[c] = v;
                    p += 8;
                }
                case BOOLEAN -> values[c] = buf[p++] != 0;
                case VARCHAR -> {
                    int utfLength = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
                    int start = p + 2;
                    boolean ascii = true;
                    for (int i = start; i < start + utfLength; i++) {
                        if (buf[i] <= 0) { // 0x80-0xFF (negative as a byte) or 0x00
                            ascii = false;
                            break;
                        }
                    }
                    values[c] = ascii
                        ? new String(buf, start, utfLength, java.nio.charset.StandardCharsets.ISO_8859_1)
                        : readModifiedUtf8(buf, p, utfLength + 2);
                    p = start + utfLength;
                }
            }
        }
        return new Row(java.util.Arrays.asList(values));
    }

    private static String readModifiedUtf8(byte[] buf, int offset, int length) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf, offset, length))) {
            return in.readUTF();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
