package com.strata.index.hash;

import com.strata.catalog.DataType;
import com.strata.storage.HeapTable;
import com.strata.storage.RecordId;
import com.strata.storage.SlottedPage;
import com.strata.storage.page.PageStore;
import com.strata.storage.page.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A persistent <b>static hash index</b>: a fixed number of buckets, each holding
 * {@code (key, RecordId)} entries for whichever keys happen to hash into it. Supports exact-match
 * {@link #search}, {@link #insert}, and {@link #delete} — the "Search, Insert, Delete" trio named
 * in the project plan. {@code Split}/{@code Merge} — growing or shrinking the number of buckets
 * as data volume changes — are a real technique (extendible or linear hashing) deliberately not
 * implemented here; see the "what's deferred" section below.
 *
 * <h2>The one big design trick: a bucket is just a {@code HeapTable}</h2>
 * A hash bucket needs exactly what a {@code HeapTable} (Phase 1) already provides: somewhere to
 * insert, scan, and delete an open-ended number of variable-length byte records, growing across
 * multiple pages as needed. So each bucket <em>is</em> a {@code HeapTable} — its entries are
 * {@code (key, RecordId)} pairs encoded by {@link EntryCodec} and stored as ordinary
 * {@code HeapTable} records. This means a bucket that outgrows its first page overflows into
 * more linked pages completely for free, using the exact same page-chaining mechanism
 * {@code HeapTable} already had — nothing new had to be built for that.
 *
 * <h2>The "directory": N buckets from just two numbers</h2>
 * {@link #create} allocates all {@code numBuckets} bucket-root pages in one tight loop, so —
 * exactly like {@link com.strata.catalog.Catalog}'s page-0 convention in Phase 2 — they come out
 * with contiguous page ids by construction (nothing else can allocate a page in between, since
 * there's no concurrency yet). That means bucket {@code i}'s root page is always
 * {@code directoryFirstPageId + i}, so the entire index can be reopened from just two integers:
 * {@link #getDirectoryFirstPageId()} and {@link #getNumBuckets()}. A future catalog integration
 * would only need to persist exactly those two numbers per index.
 *
 * <h2>A subtle correctness trap: hashing must not depend on boxed type</h2>
 * {@link #insert} accepts a boxed {@code Integer} for a {@code BIGINT}-typed index as a
 * convenience (widening never loses data — see {@link EntryCodec}). But if bucket selection used
 * {@code key.hashCode()} directly, {@code Integer.valueOf(5)} and {@code Long.valueOf(5L)} could
 * hash to <em>different</em> buckets — {@code Integer.hashCode()} and {@code Long.hashCode()}
 * aren't guaranteed to agree for every value — which would silently break the one invariant a
 * hash structure absolutely cannot violate: equal keys must always land in the same bucket.
 * {@link #hashKey} avoids this by normalizing every {@code Number} through
 * {@code Long.hashCode(value.longValue())} before hashing, so the same logical number always
 * hashes the same way no matter how it's boxed.
 *
 * <h2>What's deliberately deferred</h2>
 * <ul>
 *   <li><b>No dynamic resizing (no "Split"/"Merge").</b> {@code numBuckets} is fixed for the
 *       life of the index. A bucket that gets many entries just grows a longer page chain — for
 *       classroom-scale data this degrades gracefully rather than breaking, but a real workload
 *       would eventually want extendible or linear hashing to rebalance buckets as they fill.
 *       This is exactly the "static hashing with overflow chaining" baseline before that.</li>
 *   <li><b>Not wired into the catalog or SQL yet.</b> There's no {@code CREATE INDEX} statement
 *       in the parser, and {@code Executor} doesn't know this class exists — every
 *       {@code SELECT} still does a full table scan, even for a column that has an index. Making
 *       the executor actually choose between a scan and an index lookup is a query-planning
 *       decision, which is explicitly Phase 10's job (the cost-based optimizer), once there's
 *       finally more than one possible way to run a query.</li>
 *   <li><b>No B+Tree yet</b> — a hash index only ever answers "give me the row(s) with exactly
 *       this key"; it has no notion of order, so it can't help with a range condition like
 *       {@code balance > 100} or with satisfying an {@code ORDER BY} for free. That's what the
 *       B+Tree (the next part of this phase) is for.</li>
 * </ul>
 */
public final class HashIndex {

    private final PageStore pageStore;
    private final DataType keyType;
    private final int directoryFirstPageId;
    private final int numBuckets;

    private HashIndex(PageStore pageStore, DataType keyType, int directoryFirstPageId, int numBuckets) {
        this.pageStore = pageStore;
        this.keyType = keyType;
        this.directoryFirstPageId = directoryFirstPageId;
        this.numBuckets = numBuckets;
    }

    /** Creates a brand-new index with {@code numBuckets} empty buckets, over keys of {@code keyType}. */
    public static HashIndex create(PageStore pageStore, DataType keyType, int numBuckets) throws IOException {
        if (numBuckets < 1) {
            throw new IllegalArgumentException("numBuckets must be at least 1, got " + numBuckets);
        }

        int directoryFirstPageId = -1;
        for (int i = 0; i < numBuckets; i++) {
            int pageId = pageStore.allocatePage();
            if (i == 0) {
                directoryFirstPageId = pageId;
            } else if (pageId != directoryFirstPageId + i) {
                // Should be unreachable given how PageStore implementations hand out page ids with no
                // concurrent allocation possible yet — but a violated invariant here would
                // silently corrupt every bucket lookup, so fail loudly instead.
                throw new IllegalStateException(
                    "Internal invariant violated: hash index bucket pages must be allocated contiguously");
            }
            Page page = pageStore.readPage(pageId);
            SlottedPage.format(page);
            pageStore.writePage(pageId, page);
        }
        return new HashIndex(pageStore, keyType, directoryFirstPageId, numBuckets);
    }

    /** Reopens an existing index, given the two numbers that fully describe it. */
    public static HashIndex open(PageStore pageStore, DataType keyType, int directoryFirstPageId, int numBuckets) {
        return new HashIndex(pageStore, keyType, directoryFirstPageId, numBuckets);
    }

    public int getDirectoryFirstPageId() {
        return directoryFirstPageId;
    }

    public int getNumBuckets() {
        return numBuckets;
    }

    /** Adds a {@code (key, rid)} entry. Duplicate keys are allowed — each becomes a separate entry. */
    public void insert(Object key, RecordId rid) throws IOException {
        requireNonNullKey(key);
        HeapTable bucket = bucketFor(key);
        bucket.insert(EntryCodec.encode(keyType, key, rid));
    }

    /** Every {@link RecordId} currently indexed under {@code key}, or an empty list if none. */
    public List<RecordId> search(Object key) throws IOException {
        requireNonNullKey(key);
        HeapTable bucket = bucketFor(key);
        List<RecordId> results = new ArrayList<>();
        for (byte[] raw : bucket.scanAll()) {
            EntryCodec.Entry entry = EntryCodec.decode(keyType, raw);
            if (keysEqual(entry.key(), key)) {
                results.add(entry.recordId());
            }
        }
        return results;
    }

    /**
     * Removes the specific {@code (key, rid)} entry, if present. Returns whether anything was
     * removed. Only that one mapping is deleted — other entries for the same key (from
     * duplicate keys) are untouched.
     */
    public boolean delete(Object key, RecordId rid) throws IOException {
        requireNonNullKey(key);
        HeapTable bucket = bucketFor(key);
        for (HeapTable.StoredRecord stored : bucket.scanAllWithIds()) {
            EntryCodec.Entry entry = EntryCodec.decode(keyType, stored.data());
            if (keysEqual(entry.key(), key) && entry.recordId().equals(rid)) {
                bucket.delete(stored.id());
                return true;
            }
        }
        return false;
    }

    // ---- internals ----

    private HeapTable bucketFor(Object key) {
        int bucketIndex = Math.floorMod(hashKey(key), numBuckets);
        return HeapTable.open(pageStore, directoryFirstPageId + bucketIndex);
    }

    /** See the "subtle correctness trap" section of the class docs. */
    private static int hashKey(Object key) {
        if (key instanceof Number n) {
            return Long.hashCode(n.longValue());
        }
        return key.hashCode();
    }

    private static boolean keysEqual(Object a, Object b) {
        if (a instanceof Number an && b instanceof Number bn) {
            return an.longValue() == bn.longValue();
        }
        return a.equals(b);
    }

    private static void requireNonNullKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Cannot index a NULL key");
        }
    }
}
