package com.strata.bench.micro;

import com.strata.buffer.BufferPool;
import com.strata.storage.SlottedPage;
import com.strata.storage.page.Page;
import com.strata.storage.page.PageStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Page-level costs: typed reads inside a page, slotted-page operations, and a buffer pool cache hit. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class PageBenchmark {

    private Page page;
    private Page fullSlotted;
    private BufferPool pool;
    private final byte[] record = new byte[60];

    @Setup
    public void setup() throws IOException {
        page = new Page();
        for (int i = 0; i < Page.PAGE_SIZE / 4; i++) page.putInt(i * 4, i);

        fullSlotted = new Page();
        SlottedPage sp = SlottedPage.format(fullSlotted);
        while (sp.canFit(record.length)) sp.insertRecord(record);

        pool = new BufferPool(new InMemoryStore(), 64);
        pool.allocatePage();
        pool.readPage(0); // cached from here on
    }

    /** 1024 getInt calls; each creates a ByteBuffer view, which escape analysis may or may not remove. */
    @Benchmark
    public long readAllIntsOfAPage() {
        long sum = 0;
        for (int i = 0; i < Page.PAGE_SIZE / 4; i++) sum += page.getInt(i * 4);
        return sum;
    }

    /** Fill a fresh slotted page with 60-byte records (a page format + ~60 inserts). */
    @Benchmark
    public int fillSlottedPage() {
        SlottedPage sp = SlottedPage.format(new Page());
        int n = 0;
        while (sp.canFit(record.length)) {
            sp.insertRecord(record);
            n++;
        }
        return n;
    }

    /** Read every live record of a full slotted page (what a heap scan does per page). */
    @Benchmark
    public void readAllRecordsOfASlottedPage(Blackhole bh) {
        SlottedPage sp = new SlottedPage(fullSlotted);
        for (int i = 0; i < sp.getNumSlots(); i++) {
            if (!sp.isDeleted(i)) bh.consume(sp.getRecord(i));
        }
    }

    /** A buffer pool hit: a lock, an LRU reorder, and a defensive 4 KiB copy. */
    @Benchmark
    public Page bufferPoolHit() throws IOException {
        return pool.readPage(0);
    }

    /** Baseline for the above: the copy alone. */
    @Benchmark
    public Page pageCopyAlone() {
        return page.copy();
    }

    /** A PageStore with no I/O, so benchmarks measure CPU work only. */
    static final class InMemoryStore implements PageStore {
        private final Map<Integer, Page> pages = new HashMap<>();

        @Override
        public Page readPage(int pageId) {
            return pages.get(pageId).copy();
        }

        @Override
        public void writePage(int pageId, Page p) {
            pages.put(pageId, p.copy());
        }

        @Override
        public int allocatePage() {
            int id = pages.size();
            pages.put(id, new Page());
            return id;
        }

        @Override
        public int getNumPages() {
            return pages.size();
        }
    }
}
