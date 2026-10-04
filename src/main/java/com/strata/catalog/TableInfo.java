package com.strata.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the catalog knows about one table: its name, the first page id of its
 * {@link com.strata.storage.HeapTable} (i.e. where its actual row data lives), and its
 * column schema.
 *
 * <p>A {@code TableInfo} is itself stored as a record inside the catalog's own table (see
 * {@link Catalog}) — so, unlike {@code Page}'s fixed-offset binary format, this class needs to
 * serialize itself to and from a self-contained, variable-length {@code byte[]}. We reach for
 * {@link DataOutputStream}/{@link DataInputStream} here rather than manual {@code ByteBuffer}
 * offset math: this data doesn't need random access into individual fields the way a page's
 * contents do — it's read and written as one whole unit — so a simple sequential,
 * length-prefixed stream format is the right level of "custom binary format" for the job.
 */
public final class TableInfo {

    private final String name;
    private final int firstPageId;
    private final List<ColumnDefinition> columns;

    public TableInfo(String name, int firstPageId, List<ColumnDefinition> columns) {
        this.name = Objects.requireNonNull(name, "table name");
        this.firstPageId = firstPageId;
        this.columns = List.copyOf(columns);
    }

    public String getName() {
        return name;
    }

    public int getFirstPageId() {
        return firstPageId;
    }

    /** Unmodifiable, in declared order. */
    public List<ColumnDefinition> getColumns() {
        return columns;
    }

    /** Encodes this table's metadata as a self-contained byte array, suitable for storing as a catalog record. */
    public byte[] serialize() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(name);
            out.writeInt(firstPageId);
            out.writeInt(columns.size());
            for (ColumnDefinition column : columns) {
                out.writeUTF(column.getName());
                out.writeUTF(column.getType().name());
            }
        } catch (IOException e) {
            // ByteArrayOutputStream never actually throws IOException; this can't happen in practice.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Decodes a byte array previously produced by {@link #serialize()}. */
    public static TableInfo deserialize(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String name = in.readUTF();
            int firstPageId = in.readInt();
            int numColumns = in.readInt();
            List<ColumnDefinition> columns = new ArrayList<>(numColumns);
            for (int i = 0; i < numColumns; i++) {
                String columnName = in.readUTF();
                DataType type = DataType.valueOf(in.readUTF());
                columns.add(new ColumnDefinition(columnName, type));
            }
            return new TableInfo(name, firstPageId, columns);
        } catch (IOException e) {
            // ByteArrayInputStream never actually throws IOException; this can't happen in practice.
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableInfo other)) return false;
        return firstPageId == other.firstPageId
            && name.equals(other.name)
            && columns.equals(other.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, firstPageId, columns);
    }

    @Override
    public String toString() {
        return "TableInfo{name='" + name + "', firstPageId=" + firstPageId + ", columns=" + columns + "}";
    }
}
