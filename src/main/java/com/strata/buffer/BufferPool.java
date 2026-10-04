package com.strata.buffer;

import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An in-memory cache of pages sitting in front of a real {@link PageStore} (normally a
 * {@code DiskManager}), so that reading or writing the same page repeatedly doesn't pay a real
 * disk I/O cost every single time. This is the third class in the project to implement
 * {@link PageStore} — after {@code Transaction} (Phase 7) — reusing the exact same seam for a
 * completely different purpose: {@code Transaction} buffers writes for atomicity;
 * {@code BufferPool} caches pages for speed. Neither one needed the other to change.
 *
 * <h2>Reads: cache, then fall through</h2>
 * {@link #readPage} returns a cached copy if one exists, without touching the real store at
 * all. A cache miss reads through to the real store once and remembers the result.
 *
 * <h2>Writes: deferred, not immediate ("dirty pages")</h2>
 * {@link #writePage} only updates the in-memory cache and marks that page <b>dirty</b> — it does
 * <em>not</em> write through to the real store. A dirty page's change only reaches disk when
 * that page is evicted, or {@link #flushAll()} is called explicitly. This is what actually
 * buys the performance win: a page that's modified several times before ever being evicted
 * only costs one real disk write (whenever it's eventually flushed), not one per modification.
 *
 * <h2>Eviction: least-recently-used, via {@code LinkedHashMap}</h2>
 * The cache is a {@link LinkedHashMap} constructed in <em>access-order</em> mode, which
 * Java's standard library already maintains for us — every {@code get} or {@code put} moves
 * that entry to the "most recently used" end automatically. Overriding
 * {@link LinkedHashMap#removeEldestEntry} lets the cache evict the least-recently-used page the
 * moment it grows past capacity, flushing it first if it's dirty (evicting a dirty page without
 * flushing it would silently lose data).
 *
 * <h2>A durability question this class reopens — and how {@code Session} answers it</h2>
 * Phase 7 pointed at Phase 0's fsync-on-every-write as the source of transaction durability.
 * Introducing deferred writes here puts that at risk: if a "committed" transaction's pages only
 * ever reached this in-memory cache and nothing forced them to disk, a crash would lose data
 * that was supposedly already committed. This class doesn't solve that by itself — it only
 * provides {@link #flushAll()} as a tool. It's {@code Session} that calls
 * {@code bufferPool.flushAll()} immediately after every {@code COMMIT}, restoring the same
 * durability guarantee Phase 7 had. In database terminology this combination is called
 * <b>NO-STEAL / FORCE</b>: "no-steal" because an in-progress transaction's uncommitted writes
 * never reach this cache at all (they live in {@code Transaction}'s own private buffer until
 * its own commit), and "force" because every dirty page a transaction produced is pushed to
 * disk before its commit is acknowledged. It's the simplest correct combination — and notably,
 * it's simple specifically *because* it doesn't need a Write-Ahead Log. Phase 11 is where that
 * changes, once "steal" (letting a large, still-uncommitted transaction's pages be written to
 * disk early, under memory pressure) becomes worth doing — and undoing a stolen-but-aborted
 * page is exactly the kind of thing a WAL exists to make safe.
 */
public final class BufferPool implements PageStore {

    private static final class Frame {
        Page page;
        boolean dirty;
    }

    private final PageStore diskStore;
    private final int capacity;
    private final LinkedHashMap<Integer, Frame> cache;

    public BufferPool(PageStore diskStore, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.diskStore = diskStore;
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Frame> eldest) {
                if (size() <= BufferPool.this.capacity) {
                    return false;
                }
                try {
                    flushFrame(eldest.getKey(), eldest.getValue());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return true;
            }
        };
    }

    @Override
    public synchronized Page readPage(int pageId) throws IOException {
        Frame frame = cache.get(pageId); // access-order LinkedHashMap: this counts as "recently used"
        if (frame != null) {
            return frame.page.copy(); // never hand out the cache's own live object — see Page.copy()
        }
        Page page = diskStore.readPage(pageId);
        Frame newFrame = new Frame();
        newFrame.page = page; // safe to keep as-is: fresh from diskStore, nothing else references it yet
        cache.put(pageId, newFrame);
        return page.copy();
    }

    @Override
    public synchronized void writePage(int pageId, Page page) throws IOException {
        Frame frame = cache.get(pageId);
        if (frame == null) {
            frame = new Frame();
            cache.put(pageId, frame);
        }
        frame.page = page.copy(); // defensive copy: the caller may keep mutating their own object afterward
        frame.dirty = true;
        // Deliberately does NOT call diskStore.writePage here — see class docs.
    }

    @Override
    public synchronized int allocatePage() throws IOException {
        // Allocation grows the real file's page count, which only one source of truth (the
        // real store) should ever decide — so this always goes straight through, unlike an
        // ordinary write.
        int newPageId = diskStore.allocatePage();
        Frame frame = new Frame();
        frame.page = diskStore.readPage(newPageId); // already durable, zero-filled
        cache.put(newPageId, frame);
        return newPageId;
    }

    @Override
    public int getNumPages() {
        return diskStore.getNumPages();
    }

    /** Writes every currently-dirty cached page through to the real store. */
    public synchronized void flushAll() throws IOException {
        for (Map.Entry<Integer, Frame> entry : cache.entrySet()) {
            flushFrame(entry.getKey(), entry.getValue());
        }
    }

    private void flushFrame(int pageId, Frame frame) throws IOException {
        if (frame.dirty) {
            diskStore.writePage(pageId, frame.page);
            frame.dirty = false;
        }
    }
}
