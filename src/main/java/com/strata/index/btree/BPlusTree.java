package com.strata.index.btree;

import com.strata.index.SecondaryIndex;
import com.strata.storage.RecordId;
import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A disk-resident B+Tree over numeric keys: an ordered index supporting point lookups and range
 * scans in O(log N) page reads, where the hash index needs O(N / buckets) for lookups and can't
 * do ranges at all.
 *
 * <h2>Entries and ordering</h2>
 * Every entry is {@code (key, rid)} and entries are ordered by key, then by RecordId. Including
 * the RecordId makes every entry unique even when keys repeat (a non-unique index), so inserts,
 * deletes and navigation never need special cases for duplicates: deleting means finding one
 * exact entry, and "all rids for key k" is the range from {@code (k, min rid)} to
 * {@code (k, max rid)}.
 *
 * <h2>Keys</h2>
 * Keys are {@code long}: INTEGER and BIGINT values directly, BOOLEAN as 0/1. Fixed-size keys keep
 * nodes simple arrays with binary search. VARCHAR keys would need variable-length node layouts;
 * VARCHAR columns use the hash index instead (see {@code Executor}).
 *
 * <h2>Pages</h2>
 * <pre>
 *   meta page:     magic "BTRE" (int) | root page id (int)
 *   node header:   type (byte: 1 leaf, 2 internal) | numEntries (int) | nextLeaf (int) | child0 (int)
 *   leaf entry:    key (long) | rid.pageId (int) | rid.slot (int)                      16 bytes, 255 per leaf
 *   internal entry: key (long) | rid.pageId (int) | rid.slot (int) | right child (int)  20 bytes, 204 per node
 * </pre>
 * An internal node with n entries has n + 1 children: {@code child0}, then each entry's right
 * child. Subtree invariant: everything under the child left of separator s is {@code < s}, and
 * everything right of it is {@code >= s}. Leaves are linked left to right for range scans.
 * The meta page gives the tree a fixed address (stored in the catalog) even though the root
 * moves when it splits.
 *
 * <h2>Deletes don't merge</h2>
 * Deleting just removes the entry from its leaf; underfull (even empty) nodes are left in place
 * and scans step over empty leaves. The invariants above still hold, so lookups stay correct.
 * PostgreSQL's B-tree likewise never merges non-empty pages; reclaiming empty ones would come
 * with a free-page list.
 *
 * <h2>Concurrency and atomicity</h2>
 * None here, deliberately: the tree is accessed through a {@code Transaction} (private page
 * overlay, atomic commit via the WAL) under the table's lock, exactly like heap pages.
 */
public final class BPlusTree implements SecondaryIndex {

    static final int MAGIC = 0x42545245; // "BTRE"
    static final byte LEAF = 1;
    static final byte INTERNAL = 2;

    private static final int TYPE = 0;
    private static final int COUNT = 1;
    private static final int NEXT_LEAF = 5;
    private static final int CHILD0 = 9;
    private static final int ENTRIES = 16;
    private static final int LEAF_ENTRY = 16;
    private static final int INTERNAL_ENTRY = 20;
    static final int MAX_LEAF_ENTRIES = (Page.PAGE_SIZE - ENTRIES) / LEAF_ENTRY;
    static final int MAX_INTERNAL_ENTRIES = (Page.PAGE_SIZE - ENTRIES) / INTERNAL_ENTRY;
    private static final int NO_PAGE = -1;

    private final PageStore store;
    private final int metaPageId;

    private BPlusTree(PageStore store, int metaPageId) {
        this.store = store;
        this.metaPageId = metaPageId;
    }

    /** Creates an empty tree: a meta page and an empty root leaf. */
    public static BPlusTree create(PageStore store) throws IOException {
        int meta = store.allocatePage();
        int root = store.allocatePage();
        Page leaf = store.readPage(root);
        initNode(leaf, LEAF);
        store.writePage(root, leaf);
        Page metaPage = store.readPage(meta);
        metaPage.putInt(0, MAGIC);
        metaPage.putInt(4, root);
        store.writePage(meta, metaPage);
        return new BPlusTree(store, meta);
    }

    public static BPlusTree open(PageStore store, int metaPageId) {
        return new BPlusTree(store, metaPageId);
    }

    public int getMetaPageId() {
        return metaPageId;
    }

    // ---- public operations ----

    @Override
    public void insert(Object key, RecordId rid) throws IOException {
        long k = toKey(key);
        int root = rootPageId();
        Split split = insertInto(root, k, rid);
        if (split != null) {
            // The root split: grow the tree by one level.
            int newRoot = store.allocatePage();
            Page page = store.readPage(newRoot);
            initNode(page, INTERNAL);
            page.putInt(CHILD0, root);
            setInternalEntry(page, 0, split.key, split.rid, split.rightPageId);
            setCount(page, 1);
            store.writePage(newRoot, page);
            setRoot(newRoot);
        }
    }

    @Override
    public boolean delete(Object key, RecordId rid) throws IOException {
        long k = toKey(key);
        int pageId = findLeaf(k, rid.getPageId(), rid.getSlotIndex());
        Page leaf = store.readPage(pageId);
        int n = count(leaf);
        int pos = lowerBoundLeaf(leaf, n, k, rid.getPageId(), rid.getSlotIndex());
        if (pos >= n || compare(leafKey(leaf, pos), leafPid(leaf, pos), leafSlot(leaf, pos), k, rid.getPageId(), rid.getSlotIndex()) != 0) {
            return false;
        }
        byte[] data = leaf.getData();
        int at = ENTRIES + pos * LEAF_ENTRY;
        System.arraycopy(data, at + LEAF_ENTRY, data, at, (n - pos - 1) * LEAF_ENTRY);
        setCount(leaf, n - 1);
        store.writePage(pageId, leaf);
        return true;
    }

    @Override
    public List<RecordId> search(Object key) throws IOException {
        long k = toKey(key);
        return range(k, true, k, true);
    }

    /**
     * RecordIds of every entry with key in the given range, in key order. A null bound means
     * unbounded on that side.
     */
    public List<RecordId> range(Long low, boolean lowInclusive, Long high, boolean highInclusive) throws IOException {
        List<RecordId> result = new ArrayList<>();
        int pageId;
        int pos;
        if (low == null) {
            pageId = leftmostLeaf();
            pos = 0;
        } else {
            // Position at the first entry >= (low, smallest rid) — or > (low, largest rid) if exclusive.
            int pid = lowInclusive ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            int slot = lowInclusive ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            pageId = findLeaf(low, pid, slot);
            Page leaf = store.readPage(pageId);
            pos = lowerBoundLeaf(leaf, count(leaf), low, pid, slot);
        }
        while (pageId != NO_PAGE) {
            Page leaf = store.readPage(pageId);
            int n = count(leaf);
            for (int i = pos; i < n; i++) {
                long k = leafKey(leaf, i);
                if (low != null && (k < low || (!lowInclusive && k == low))) continue;
                if (high != null && (k > high || (!highInclusive && k == high))) return result;
                result.add(new RecordId(leafPid(leaf, i), leafSlot(leaf, i)));
            }
            pageId = leaf.getInt(NEXT_LEAF);
            pos = 0;
        }
        return result;
    }

    // ---- insertion ----

    /** A node split: the separator to push up, and the new right sibling. */
    private record Split(long key, RecordId rid, int rightPageId) {}

    private Split insertInto(int pageId, long k, RecordId rid) throws IOException {
        Page page = store.readPage(pageId);
        if (page.getByte(TYPE) == LEAF) {
            return insertIntoLeaf(pageId, page, k, rid);
        }
        int n = count(page);
        int childIndex = childIndexFor(page, n, k, rid.getPageId(), rid.getSlotIndex());
        Split childSplit = insertInto(childAt(page, childIndex), k, rid);
        if (childSplit == null) {
            return null;
        }
        // The child at childIndex split: its new sibling becomes entry childIndex (right child).
        page = store.readPage(pageId); // re-read: the recursive call may have dirtied other pages, not this one
        if (n < MAX_INTERNAL_ENTRIES) {
            shiftInternal(page, childIndex, n);
            setInternalEntry(page, childIndex, childSplit.key, childSplit.rid, childSplit.rightPageId);
            setCount(page, n + 1);
            store.writePage(pageId, page);
            return null;
        }
        return splitInternal(pageId, page, n, childIndex, childSplit);
    }

    private Split insertIntoLeaf(int pageId, Page leaf, long k, RecordId rid) throws IOException {
        int n = count(leaf);
        int pos = lowerBoundLeaf(leaf, n, k, rid.getPageId(), rid.getSlotIndex());
        if (n < MAX_LEAF_ENTRIES) {
            byte[] data = leaf.getData();
            int at = ENTRIES + pos * LEAF_ENTRY;
            System.arraycopy(data, at, data, at + LEAF_ENTRY, (n - pos) * LEAF_ENTRY);
            setLeafEntry(leaf, pos, k, rid);
            setCount(leaf, n + 1);
            store.writePage(pageId, leaf);
            return null;
        }
        // Full: build the n+1 sorted entries, keep the lower half here, move the upper half right.
        long[] keys = new long[n + 1];
        int[] pids = new int[n + 1];
        int[] slots = new int[n + 1];
        for (int i = 0, j = 0; i <= n; i++) {
            if (i == pos) {
                keys[i] = k;
                pids[i] = rid.getPageId();
                slots[i] = rid.getSlotIndex();
            } else {
                keys[i] = leafKey(leaf, j);
                pids[i] = leafPid(leaf, j);
                slots[i] = leafSlot(leaf, j);
                j++;
            }
        }
        int leftCount = (n + 1) / 2;
        int rightId = store.allocatePage();
        Page right = store.readPage(rightId);
        initNode(right, LEAF);
        for (int i = leftCount; i <= n; i++) {
            setLeafEntry(right, i - leftCount, keys[i], new RecordId(pids[i], slots[i]));
        }
        setCount(right, n + 1 - leftCount);
        right.putInt(NEXT_LEAF, leaf.getInt(NEXT_LEAF));
        for (int i = 0; i < leftCount; i++) {
            setLeafEntry(leaf, i, keys[i], new RecordId(pids[i], slots[i]));
        }
        setCount(leaf, leftCount);
        leaf.putInt(NEXT_LEAF, rightId);
        store.writePage(rightId, right);
        store.writePage(pageId, leaf);
        // The separator is a copy of the right node's first entry (it stays in the leaf too).
        return new Split(keys[leftCount], new RecordId(pids[leftCount], slots[leftCount]), rightId);
    }

    /** Splits a full internal node while inserting {@code childSplit} at entry position {@code pos}. */
    private Split splitInternal(int pageId, Page page, int n, int pos, Split childSplit) throws IOException {
        long[] keys = new long[n + 1];
        int[] pids = new int[n + 1];
        int[] slots = new int[n + 1];
        int[] rightChildren = new int[n + 1];
        for (int i = 0, j = 0; i <= n; i++) {
            if (i == pos) {
                keys[i] = childSplit.key;
                pids[i] = childSplit.rid.getPageId();
                slots[i] = childSplit.rid.getSlotIndex();
                rightChildren[i] = childSplit.rightPageId;
            } else {
                keys[i] = internalKey(page, j);
                pids[i] = internalPid(page, j);
                slots[i] = internalSlot(page, j);
                rightChildren[i] = internalChild(page, j);
                j++;
            }
        }
        int mid = (n + 1) / 2; // this separator moves up; it is not kept in either half
        int rightId = store.allocatePage();
        Page right = store.readPage(rightId);
        initNode(right, INTERNAL);
        right.putInt(CHILD0, rightChildren[mid]);
        for (int i = mid + 1; i <= n; i++) {
            setInternalEntry(right, i - mid - 1, keys[i], new RecordId(pids[i], slots[i]), rightChildren[i]);
        }
        setCount(right, n - mid);
        for (int i = 0; i < mid; i++) {
            setInternalEntry(page, i, keys[i], new RecordId(pids[i], slots[i]), rightChildren[i]);
        }
        setCount(page, mid);
        store.writePage(rightId, right);
        store.writePage(pageId, page);
        return new Split(keys[mid], new RecordId(pids[mid], slots[mid]), rightId);
    }

    private static void shiftInternal(Page page, int from, int n) {
        byte[] data = page.getData();
        int at = ENTRIES + from * INTERNAL_ENTRY;
        System.arraycopy(data, at, data, at + INTERNAL_ENTRY, (n - from) * INTERNAL_ENTRY);
    }

    // ---- navigation ----

    private int findLeaf(long k, int pid, int slot) throws IOException {
        int pageId = rootPageId();
        while (true) {
            Page page = store.readPage(pageId);
            if (page.getByte(TYPE) == LEAF) {
                return pageId;
            }
            pageId = childAt(page, childIndexFor(page, count(page), k, pid, slot));
        }
    }

    private int leftmostLeaf() throws IOException {
        int pageId = rootPageId();
        while (true) {
            Page page = store.readPage(pageId);
            if (page.getByte(TYPE) == LEAF) {
                return pageId;
            }
            pageId = page.getInt(CHILD0);
        }
    }

    /** Which child to descend into: 0 = child0, i = right child of separator i-1. Number of separators <= entry. */
    private static int childIndexFor(Page page, int n, long k, int pid, int slot) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(internalKey(page, mid), internalPid(page, mid), internalSlot(page, mid), k, pid, slot) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int childAt(Page page, int childIndex) {
        return childIndex == 0 ? page.getInt(CHILD0) : internalChild(page, childIndex - 1);
    }

    /** First position whose entry is >= (k, pid, slot). */
    private static int lowerBoundLeaf(Page leaf, int n, long k, int pid, int slot) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(leafKey(leaf, mid), leafPid(leaf, mid), leafSlot(leaf, mid), k, pid, slot) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    static int compare(long k1, int p1, int s1, long k2, int p2, int s2) {
        int c = Long.compare(k1, k2);
        if (c != 0) return c;
        c = Integer.compare(p1, p2);
        return c != 0 ? c : Integer.compare(s1, s2);
    }

    // ---- meta page ----

    private int rootPageId() throws IOException {
        Page meta = store.readPage(metaPageId);
        if (meta.getInt(0) != MAGIC) {
            throw new IllegalStateException("Page " + metaPageId + " is not a B+Tree meta page");
        }
        return meta.getInt(4);
    }

    private void setRoot(int root) throws IOException {
        Page meta = store.readPage(metaPageId);
        meta.putInt(4, root);
        store.writePage(metaPageId, meta);
    }

    // ---- node layout ----

    private static void initNode(Page page, byte type) {
        page.putByte(TYPE, type);
        page.putInt(COUNT, 0);
        page.putInt(NEXT_LEAF, NO_PAGE);
        page.putInt(CHILD0, NO_PAGE);
    }

    static int count(Page page) {
        return page.getInt(COUNT);
    }

    private static void setCount(Page page, int n) {
        page.putInt(COUNT, n);
    }

    static long leafKey(Page p, int i) {
        return p.getLong(ENTRIES + i * LEAF_ENTRY);
    }

    static int leafPid(Page p, int i) {
        return p.getInt(ENTRIES + i * LEAF_ENTRY + 8);
    }

    static int leafSlot(Page p, int i) {
        return p.getInt(ENTRIES + i * LEAF_ENTRY + 12);
    }

    private static void setLeafEntry(Page p, int i, long k, RecordId rid) {
        int at = ENTRIES + i * LEAF_ENTRY;
        p.putLong(at, k);
        p.putInt(at + 8, rid.getPageId());
        p.putInt(at + 12, rid.getSlotIndex());
    }

    static long internalKey(Page p, int i) {
        return p.getLong(ENTRIES + i * INTERNAL_ENTRY);
    }

    static int internalPid(Page p, int i) {
        return p.getInt(ENTRIES + i * INTERNAL_ENTRY + 8);
    }

    static int internalSlot(Page p, int i) {
        return p.getInt(ENTRIES + i * INTERNAL_ENTRY + 12);
    }

    static int internalChild(Page p, int i) {
        return p.getInt(ENTRIES + i * INTERNAL_ENTRY + 16);
    }

    private static void setInternalEntry(Page p, int i, long k, RecordId rid, int rightChild) {
        int at = ENTRIES + i * INTERNAL_ENTRY;
        p.putLong(at, k);
        p.putInt(at + 8, rid.getPageId());
        p.putInt(at + 12, rid.getSlotIndex());
        p.putInt(at + 16, rightChild);
    }

    /** Index keys as longs: INTEGER/BIGINT as-is, BOOLEAN as 0/1. */
    public static long toKey(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        throw new IllegalArgumentException("B+Tree keys must be numeric or boolean, got "
            + (value == null ? "NULL" : value.getClass().getSimpleName()));
    }

    // ---- verification (tests) ----

    /**
     * Checks every structural invariant (sorted nodes, separator bounds, uniform leaf depth,
     * leaf chain in global order) and returns the number of entries. Throws on violation.
     */
    public long verify() throws IOException {
        int root = rootPageId();
        long[] count = {0};
        int[] leafDepth = {-1};
        verifyNode(root, null, null, 0, leafDepth, count);
        // Leaf chain must visit every entry in non-decreasing order.
        long chained = 0;
        long[] prev = null;
        for (int pageId = leftmostLeaf(); pageId != NO_PAGE; ) {
            Page leaf = store.readPage(pageId);
            for (int i = 0; i < count(leaf); i++) {
                long[] cur = {leafKey(leaf, i), leafPid(leaf, i), leafSlot(leaf, i)};
                if (prev != null && compare(prev[0], (int) prev[1], (int) prev[2], cur[0], (int) cur[1], (int) cur[2]) >= 0) {
                    throw new IllegalStateException("leaf chain out of order at page " + pageId);
                }
                prev = cur;
                chained++;
            }
            pageId = leaf.getInt(NEXT_LEAF);
        }
        if (chained != count[0]) {
            throw new IllegalStateException("leaf chain has " + chained + " entries but the tree has " + count[0]);
        }
        return count[0];
    }

    private void verifyNode(int pageId, long[] lowerIncl, long[] upperExcl, int depth, int[] leafDepth, long[] count) throws IOException {
        Page page = store.readPage(pageId);
        int n = count(page);
        boolean leaf = page.getByte(TYPE) == LEAF;
        for (int i = 0; i < n; i++) {
            long[] e = leaf ? new long[] {leafKey(page, i), leafPid(page, i), leafSlot(page, i)}
                            : new long[] {internalKey(page, i), internalPid(page, i), internalSlot(page, i)};
            if (lowerIncl != null && cmp(e, lowerIncl) < 0) throw new IllegalStateException("entry below its subtree bound in page " + pageId);
            if (upperExcl != null && cmp(e, upperExcl) >= 0) throw new IllegalStateException("entry above its subtree bound in page " + pageId);
            if (i > 0) {
                long[] p = leaf ? new long[] {leafKey(page, i - 1), leafPid(page, i - 1), leafSlot(page, i - 1)}
                                : new long[] {internalKey(page, i - 1), internalPid(page, i - 1), internalSlot(page, i - 1)};
                if (cmp(p, e) >= 0) throw new IllegalStateException("unsorted entries in page " + pageId);
            }
        }
        if (leaf) {
            if (leafDepth[0] == -1) leafDepth[0] = depth;
            else if (leafDepth[0] != depth) throw new IllegalStateException("leaves at different depths");
            count[0] += n;
            return;
        }
        for (int c = 0; c <= n; c++) {
            long[] lo = c == 0 ? lowerIncl : new long[] {internalKey(page, c - 1), internalPid(page, c - 1), internalSlot(page, c - 1)};
            long[] hi = c == n ? upperExcl : new long[] {internalKey(page, c), internalPid(page, c), internalSlot(page, c)};
            verifyNode(childAt(page, c), lo, hi, depth + 1, leafDepth, count);
        }
    }

    private static int cmp(long[] a, long[] b) {
        return compare(a[0], (int) a[1], (int) a[2], b[0], (int) b[1], (int) b[2]);
    }
}
