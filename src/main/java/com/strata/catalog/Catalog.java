package com.strata.catalog;

import com.strata.index.hash.HashIndex;
import com.strata.storage.HeapTable;
import com.strata.storage.page.PageStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The System Catalog: the database's memory of what tables and indexes exist. Every real DBMS
 * has one of these (Postgres calls its version {@code pg_class}/{@code pg_attribute}/
 * {@code pg_index}, MySQL has {@code information_schema}, etc.) — user queries about schema, and
 * the executor's need to know "what columns does this table have and where does its data live",
 * both go through here.
 *
 * <h2>The bootstrapping problem, and how it's solved</h2>
 * The catalog needs to remember a list of tables (and, since Phase 10, indexes). But "a list of
 * records that outlives the process" is exactly what {@link HeapTable} already is — so the
 * catalog is implemented as a {@code HeapTable} <em>of itself</em>. That still leaves one
 * question: given a fresh database file, how do you find the catalog's own first page, before
 * you have anywhere to look it up? The answer is a convention rather than a lookup:
 * {@link #CATALOG_ROOT_PAGE_ID} (page 0) is <em>always</em> the catalog's own first page, for
 * every StrataDB database file, full stop — the same idea as a filesystem superblock always
 * living at a fixed, well-known location.
 *
 * <h2>Two kinds of catalog entry, one catalog table (Phase 10)</h2>
 * Table schemas ({@link TableInfo}) and index metadata ({@link IndexInfo}) are both stored as
 * records in the *same* catalog {@code HeapTable}, distinguished by a one-byte tag written just
 * before each record's own serialized bytes. This avoids needing a second well-known root page
 * — one bootstrap object, one convention, two kinds of thing it can describe. Real systems often
 * split these into separate catalog relations (Postgres has distinct {@code pg_class} and
 * {@code pg_index} tables); a single tagged table is a reasonable simplification at this scale.
 */
public final class Catalog {

    /** By convention, the catalog's own table always begins at this page in every StrataDB file. */
    public static final int CATALOG_ROOT_PAGE_ID = 0;

    private static final byte TABLE_ENTRY_TAG = 0;
    private static final byte INDEX_ENTRY_TAG = 1;

    private final PageStore pageStore;
    private final HeapTable catalogTable;

    /** In-memory caches, keyed by name, loaded once at open time and kept in sync on every create. */
    private final Map<String, TableInfo> tables = new LinkedHashMap<>();
    private final Map<String, IndexInfo> indexes = new LinkedHashMap<>();

    private Catalog(PageStore pageStore, HeapTable catalogTable) {
        this.pageStore = pageStore;
        this.catalogTable = catalogTable;
    }

    /** Initializes a brand-new database file's catalog. Call this exactly once, on an empty file. */
    public static Catalog bootstrap(PageStore pageStore) throws IOException {
        if (pageStore.getNumPages() != 0) {
            throw new IllegalStateException(
                "bootstrap() is only for a brand-new, empty database file; use open() to reopen an existing one");
        }
        HeapTable catalogTable = HeapTable.create(pageStore);
        if (catalogTable.getFirstPageId() != CATALOG_ROOT_PAGE_ID) {
            // Should be unreachable given how PageStore implementations hand out page ids, but a corrupted
            // or hand-edited file is exactly the kind of thing that should fail loudly, not quietly.
            throw new IllegalStateException(
                "Internal invariant violated: the catalog's table must be the very first page allocated");
        }
        return new Catalog(pageStore, catalogTable);
    }

    /** Reopens the catalog of an existing database file, loading every table's and index's metadata into memory. */
    public static Catalog open(PageStore pageStore) throws IOException {
        if (pageStore.getNumPages() == 0) {
            throw new IllegalStateException("Database file is empty; use bootstrap() to initialize a new database");
        }
        HeapTable catalogTable = HeapTable.open(pageStore, CATALOG_ROOT_PAGE_ID);
        Catalog catalog = new Catalog(pageStore, catalogTable);
        catalog.loadFromDisk();
        return catalog;
    }

    private void loadFromDisk() throws IOException {
        for (byte[] record : catalogTable.scanAll()) {
            byte tag = record[0];
            byte[] payload = Arrays.copyOfRange(record, 1, record.length);
            switch (tag) {
                case TABLE_ENTRY_TAG -> {
                    TableInfo info = TableInfo.deserialize(payload);
                    tables.put(info.getName(), info);
                }
                case INDEX_ENTRY_TAG -> {
                    IndexInfo info = IndexInfo.deserialize(payload);
                    indexes.put(info.indexName(), info);
                }
                default -> throw new IllegalStateException("Unknown catalog entry tag: " + tag);
            }
        }
    }

    private static byte[] tagged(byte tag, byte[] payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 1);
        try {
            bytes.write(tag);
            bytes.write(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never actually throws
        }
        return bytes.toByteArray();
    }

    // ---- tables ----

    /**
     * Registers a brand-new table: allocates its data storage and records its schema in the
     * catalog. Returns the resulting {@link TableInfo}; use {@link #openTable} afterward to
     * actually insert/read/delete rows.
     */
    public TableInfo createTable(String name, List<ColumnDefinition> columns) throws IOException {
        if (tables.containsKey(name)) {
            throw new IllegalArgumentException("Table '" + name + "' already exists");
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Table '" + name + "' must have at least one column");
        }

        HeapTable dataTable = HeapTable.create(pageStore);
        TableInfo info = new TableInfo(name, dataTable.getFirstPageId(), columns);

        catalogTable.insert(tagged(TABLE_ENTRY_TAG, info.serialize()));
        tables.put(name, info);
        return info;
    }

    /** True if a table with this name has been created. */
    public boolean tableExists(String name) {
        return tables.containsKey(name);
    }

    /** This table's schema, if it exists. */
    public Optional<TableInfo> getTable(String name) {
        return Optional.ofNullable(tables.get(name));
    }

    /** Every known table's name, in the order tables were created. */
    public List<String> listTableNames() {
        return List.copyOf(tables.keySet());
    }

    /** Opens a handle to an existing table's row data for reading/writing records. */
    public HeapTable openTable(String name) {
        TableInfo info = tables.get(name);
        if (info == null) {
            throw new NoSuchElementException("No such table: '" + name + "'");
        }
        return HeapTable.open(pageStore, info.getFirstPageId());
    }

    // ---- indexes ----

    /**
     * Registers a brand-new, empty index on {@code tableName.columnName}. Allocates the index's
     * bucket storage and records its metadata, but does <b>not</b> populate it with entries for
     * any rows the table already has — the catalog doesn't know how to interpret a row's raw
     * bytes (that's {@code RowSerializer}'s job, in the executor), so populating an index built
     * against pre-existing data is the executor's responsibility, right after this call.
     */
    public IndexInfo createIndex(String indexName, String tableName, String columnName, int numBuckets) throws IOException {
        if (indexes.containsKey(indexName)) {
            throw new IllegalArgumentException("Index '" + indexName + "' already exists");
        }
        TableInfo table = getTable(tableName)
            .orElseThrow(() -> new IllegalArgumentException("No such table: '" + tableName + "'"));
        DataType columnType = columnTypeOf(table, columnName);

        HashIndex hashIndex = HashIndex.create(pageStore, columnType, numBuckets);
        IndexInfo info = new IndexInfo(indexName, tableName, columnName, hashIndex.getDirectoryFirstPageId(), numBuckets);

        catalogTable.insert(tagged(INDEX_ENTRY_TAG, info.serialize()));
        indexes.put(indexName, info);
        return info;
    }

    /** True if an index with this name has been created. */
    public boolean indexExists(String indexName) {
        return indexes.containsKey(indexName);
    }

    /** Every index registered on {@code tableName}, in no particular order. */
    public List<IndexInfo> getIndexesForTable(String tableName) {
        List<IndexInfo> result = new ArrayList<>();
        for (IndexInfo info : indexes.values()) {
            if (info.tableName().equals(tableName)) {
                result.add(info);
            }
        }
        return result;
    }

    /** The index on {@code tableName.columnName}, if one exists — at most one is supported per column. */
    public Optional<IndexInfo> findIndexForColumn(String tableName, String columnName) {
        for (IndexInfo info : indexes.values()) {
            if (info.tableName().equals(tableName) && info.columnName().equals(columnName)) {
                return Optional.of(info);
            }
        }
        return Optional.empty();
    }

    /** Reopens a handle to an existing index for searching/inserting/deleting entries. */
    public HashIndex openIndex(IndexInfo info) {
        DataType columnType = columnTypeOf(
            getTable(info.tableName()).orElseThrow(() -> new NoSuchElementException("No such table: '" + info.tableName() + "'")),
            info.columnName());
        return HashIndex.open(pageStore, columnType, info.directoryFirstPageId(), info.numBuckets());
    }

    private static DataType columnTypeOf(TableInfo table, String columnName) {
        for (ColumnDefinition column : table.getColumns()) {
            if (column.getName().equals(columnName)) {
                return column.getType();
            }
        }
        throw new IllegalArgumentException("No such column: '" + columnName + "' in table '" + table.getName() + "'");
    }
}
