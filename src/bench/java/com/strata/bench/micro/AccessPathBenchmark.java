package com.strata.bench.micro;

import com.strata.buffer.BufferPool;
import com.strata.catalog.DataType;
import com.strata.index.hash.HashIndex;
import com.strata.storage.HeapTable;
import com.strata.storage.RecordId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Access-path costs with all pages cached (no I/O): hash index lookup as the table grows,
 * and a full heap scan. Shows how lookup cost scales with N for a fixed bucket count.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class AccessPathBenchmark {

    @Param({"1000", "10000", "100000"})
    public int rows;

    private HashIndex index;
    private HeapTable heap;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        BufferPool pool = new BufferPool(new PageBenchmark.InMemoryStore(), 1 << 16);
        index = HashIndex.create(pool, DataType.INTEGER, 16); // the executor's DEFAULT_INDEX_BUCKETS
        heap = HeapTable.create(pool);
        byte[] record = new byte[60];
        for (int i = 0; i < rows; i++) {
            index.insert(i, new RecordId(i / 60, i % 60));
        }
        for (int i = 0; i < Math.min(rows, 20_000); i++) {
            heap.insert(record);
        }
    }

    @Benchmark
    public List<RecordId> hashIndexLookup() throws IOException {
        return index.search(ThreadLocalRandom.current().nextInt(rows));
    }

    /** Scans min(rows, 20000) records; heap insert is O(pages) per row, so larger tables are slow to build. */
    @Benchmark
    public int heapScan() throws IOException {
        return heap.scanAllWithIds().size();
    }
}
