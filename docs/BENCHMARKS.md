# Benchmarks

How StrataDB's performance is measured, what the numbers are, and what they mean. Every
number here was produced by the harness in `src/bench/java` on the machine described below;
raw output for each run is committed under `bench-results/<label>/` (`results.md`,
`results.csv`, and `jmh.txt` where applicable).

**Read these numbers as relative, not absolute.** They come from one developer laptop, and
the commit path is fsync-bound, so absolute throughput depends heavily on the storage device
and OS. The comparisons between runs on the same machine are what matter.

## Running

```bash
scripts/bench.sh workload --scale medium --runs 3 --label my-run   # end-to-end workloads
scripts/bench.sh jmh -prof gc                                     # all JMH microbenchmarks
scripts/bench.sh jmh CodecBenchmark                               # one JMH class
```

On Windows use `powershell -File scripts/bench.ps1 ...` with the same arguments. Both scripts
compile the `bench` Maven profile (which adds `src/bench/java` and JMH) and run with
`-Xms1g -Xmx1g` (override with `JVM_OPTS` in the shell script).

## Two kinds of benchmark

| | JMH microbenchmarks (`com.strata.bench.micro`) | Workload harness (`WorkloadBenchmark`) |
|---|---|---|
| Measures | CPU cost of hot code paths in isolation (no I/O) | Whole-system behaviour through SQL, real files, real fsyncs |
| Examples | parse a statement, decode a row, buffer pool hit, hash index lookup vs. N | autocommit inserts, point lookups, scans, concurrent writers, read/write mix |
| Why | Tells you whether a piece of CPU work is worth optimizing | Tells you what users would see, and where the time goes (via engine metrics) |

## Methodology (workload harness)

- **Fresh database per run.** Every measured run of every scenario creates a new database file
  in a temp directory, so runs don't inherit each other's layout or cache.
- **Warm-up.** Each scenario first runs once at 1/10 size so the JIT has compiled the hot paths.
- **Repetitions.** `--runs N` measured runs (3 for the numbers below). The report shows the run
  with the **median throughput**, plus the min..max throughput spread across runs.
- **Latency.** Every operation is timed individually with `System.nanoTime()`; p50/p95/p99/max
  are exact (nearest-rank) over all samples, not estimated from a histogram.
- **Engine metrics.** `Database.metrics()` snapshots before and after the measured section give
  per-operation fsyncs, WAL bytes, buffer pool hit ratio, and total lock wait time.
- **JVM cost.** Allocated bytes (`ThreadMXBean.getTotalThreadAllocatedBytes`), GC count, and heap
  after a forced GC.
- **Buffer pool** is 1024 pages (4 MiB), large enough to hold the medium dataset, so the
  workloads measure the engine rather than cache misses (the hit ratio column confirms 1.000).
  Cache behaviour under memory pressure is a separate question.
- **Rows** are `(id INTEGER, val BIGINT, pad VARCHAR(40 chars))`, about 60 bytes encoded.

### Scenarios (medium scale)

| Scenario | What it does |
|---|---|
| `insert_autocommit` | 2,000 single-row INSERTs, each its own transaction |
| `insert_batched` | 20,000 rows in transactions of 500; latency is per transaction |
| `point_lookup_index` | 10,000 random `WHERE id = k` lookups on 20,000 rows with a hash index |
| `point_lookup_scan` | 100 such lookups without an index (full scan each) |
| `range_query` | 100 queries `id >= a AND id < a+100` (always a full scan: no ordered index yet) |
| `full_scan` | 10 × `SELECT *` over 20,000 rows; ops = rows scanned |
| `update_indexed` | 1,000 autocommit `UPDATE ... WHERE id = k` on an indexed table |
| `concurrent_insert_own_table_tN` | N threads, 300 autocommit inserts each, each thread on its own table |
| `concurrent_insert_same_table_tN` | Same, all threads on one table |
| `mixed_80read_20write_tN` | N threads on one indexed table: 80% point SELECTs, 20% autocommit INSERTs |

### Environment for the results below

```
CPU / OS:  16 logical CPUs, Windows 11 (amd64), consumer NVMe SSD
JVM:       Java HotSpot 23.0.1, G1, -Xms1g -Xmx1g
```

## Results

### Baseline (`bench-results/baseline`, commit 44283ed)

Recorded after the correctness fixes (T1–T4) and before any performance work. The original
pre-audit code is deliberately **not** the baseline: its concurrent scenarios corrupt data or
hang (audit bug 1), so any number from them would be meaningless.

| scenario | ops/s | p50 µs | p99 µs | fsyncs/op | WAL B/op | alloc KB/op |
|---|---:|---:|---:|---:|---:|---:|
| insert_autocommit | 495 | 1,771 | 4,789 | 2.03 | 4,190 | 98 |
| insert_batched (rows/s) | 9,571 | 48,512 | 93,499 | 0.04 | 74 | 663 |
| point_lookup_index | 11,065 | 95 | 192 | 0 | 0 | 281 |
| point_lookup_scan | 181 | 5,264 | 9,506 | 0 | 0 | 15,307 |
| range_query | 180 | 5,045 | 9,202 | 0 | 0 | 15,789 |
| update_indexed | 213 | 4,473 | 7,782 | 4.99 | 16,330 | 1,934 |
| concurrent_insert_own_table t1 / t8 | 550 / 709 | 1,646 / 10,587 | 3,263 / 23,802 | 2.03 | 4,179 | 42 / 48 |
| concurrent_insert_same_table t1 / t8 | 559 / 729 | 1,630 / 1,411 | 3,657 / 145,832 | 2.03 | 4,189 | 41 / 110 |
| mixed_80read_20write t1 / t8 | 1,944 / 1,595 | 76 / 93 | 3,082 / 84,628 | 0.62 / 0.60 | ~1,650 | ~520 |

What the baseline says:

1. **Commits are fsync-bound and pay double.** About 2 fsyncs per autocommit insert: one for the
   WAL record and one per dirty data page flushed at commit (the FORCE policy). An indexed
   UPDATE dirties about 4 pages, so it pays about 5.
2. **Concurrency doesn't help writers.** 8 threads on 8 different tables reach 709 ops/s versus
   550 for one thread: every commit fsyncs inside one global lock, so commits run one at a time.
3. **Tail latency explodes under contention.** One table, 8 writers: p99 146 ms, max 2.2 s.
   Table X locks are held across both fsyncs, and lock waiters are woken unfairly.
4. **Write amplification is about 70×.** 4.2 KB of WAL per ~60-byte row (full page images).
5. **Bulk insert allocates 663 KB per row.** `HeapTable.insert` walks the whole page chain from
   the first page for every row, and every page read through the buffer pool is a 4 KiB copy.
6. **The hash index degrades linearly** (see JMH below): 16 fixed buckets.

### JMH baseline (`bench-results/baseline/jmh.txt`)

| benchmark | time | allocation |
|---|---:|---:|
| `CodecBenchmark.parseSelect` / `parseInsert` | 0.71 / 0.86 µs | 1.7 / 1.8 KB |
| `CodecBenchmark.serializeRow` / `deserializeRow` | 350 / 205 ns | 416 / 552 B |
| `PageBenchmark.bufferPoolHit` | 369 ns | 4,128 B |
| `PageBenchmark.pageCopyAlone` | 427 ns | 4,128 B |
| `PageBenchmark.readAllIntsOfAPage` (1024 × `getInt`) | 113 ns | **0 B** |
| `AccessPathBenchmark.hashIndexLookup` N = 1k / 10k / 100k | 3.8 / 34 / 379 µs | 16 KB / 137 KB / 1.35 MB |
| `AccessPathBenchmark.heapScan` N = 1k / 10k | 29 / 282 µs | 213 KB / 2.1 MB |

What the JMH baseline says:

- A buffer pool hit costs the same as the 4 KiB defensive copy alone: the lock and LRU
  bookkeeping are noise; the copy *is* the cost.
- `Page.getInt` creates a `ByteBuffer` per call in the source, but allocates **0 bytes**:
  escape analysis removes it. Optimizing it would have been wasted work. (That suspicion came
  from reading the code; the measurement refuted it.)
- Parsing and row encoding cost well under 1 µs per statement or row; a commit costs about
  1 ms. CPU work is not the write bottleneck, which is why native (C++/JNI) code isn't
  justified at this stage.
- Hash index lookup time grows linearly with N: O(N/16) with 16 static buckets.

### After T6: NO-FORCE + group commit + checkpoints (`bench-results/t6-noforce-groupcommit`)

| scenario | baseline ops/s | T6 ops/s | change | p99 µs (base → T6) | fsyncs/op (base → T6) |
|---|---:|---:|---:|---:|---:|
| insert_autocommit | 495 | 1,382 | **2.8×** | 4,789 → 1,272 | 2.03 → 1.00 |
| update_indexed | 213 | 881 | **4.1×** | 7,782 → 2,051 | 4.99 → 1.00 |
| insert_batched (rows/s) | 9,571 | 12,189 | 1.3× | 93,499 → 83,614 | 0.04 → 0.00 |
| concurrent_insert_own_table t1 | 550 | 1,319 | 2.4× | 3,263 → 1,361 | 2.03 → 1.00 |
| concurrent_insert_own_table t8 | 709 | 4,215 | **5.9×** | 23,802 → 3,559 | 2.03 → 0.26 |
| concurrent_insert_same_table t8 | 729 | 1,430 | 2.0× | 145,832 → 74,406 | 2.03 → 1.00 |
| mixed_80read_20write t1 | 1,944 | 3,762 | 1.9× | 3,082 → 1,347 | 0.62 → 0.20 |
| mixed_80read_20write t8 | 1,595 | 3,330 | 2.1× | 84,628 → 43,895 | 0.60 → 0.20 |
| point lookups, scans | — | — | ≈ 1× | — | 0 |

Interpretation:

- **One fsync per commit instead of 2–5.** Commits now make only the log durable; data pages are
  written lazily (eviction, checkpoints). Single-threaded write throughput rises 2.4–4.1×.
- **Group commit scales independent writers.** With 8 threads on separate tables, an fsync
  covers about 4 commits on average (0.26 fsyncs/op), and throughput rises with thread count
  (1,319 → 4,215 ops/s for 1 → 8 threads) where it used to stay flat.
- **One hot table still serializes.** Same-table writers stay at 1.00 fsyncs/op: strict 2PL keeps
  the table's exclusive lock until the commit is durable, so no two commits on one table can
  share an fsync. Lifting this needs finer-grained (row) locks or early lock release; it is
  recorded as future work in `docs/AUDIT.md`.
- **Reads are untouched**, as expected; T7/T8 target them.
- **Not improved:** write amplification (still full page images, about 4.2 KB/row) and bulk insert
  allocation (660 KB/row) — the next targets.

### After T7: insert tail hint, in-place UPDATE, page compaction (`bench-results/t7-storage-paths`)

| scenario | T6 ops/s | T7 ops/s | change | alloc KB/op (T6 → T7) | WAL B/op (T6 → T7) |
|---|---:|---:|---:|---:|---:|
| insert_batched (rows/s) | 12,189 | 178,584 | **14.6×** | 660 → 7.2 | 74 → 74 |
| update_indexed | 881 | 1,352 | 1.5× | 1,893 → 295 | 16,342 → 4,136 |
| insert_autocommit | 1,382 | 1,566 | 1.1× | 89 → 27 | 4,202 → 4,202 |
| mixed_80read_20write t8 | 3,330 | 4,170 | 1.25× | 527 → 247 | ~1,630 |

Interpretation:

- **Bulk load was O(N²) in page reads**; with the tail hint each insert touches one or two pages.
  The 660 KB/row of allocation was 4 KiB page copies from walking ~160 pages per insert.
  Per-transaction latency for 500-row batches fell from 45 ms to 2.7 ms (p50).
- **UPDATE no longer rewrites every index.** An in-place update keeps the RecordId, so index
  entries whose key didn't change stay put: 4.1 KB of log per update (one heap page) instead
  of 16.3 KB (heap page + old and new copies + index bucket pages).
- Point lookups and scans are unchanged: they're bound by the static hash index and by
  copying every page through the buffer pool. That's T8.

### After T8: B+Tree index and range planning (`bench-results/t8-btree`)

`CREATE INDEX` on a numeric column now builds a B+Tree by default; `point_lookup_index` and
`range_query` therefore use it, and `point_lookup_hash` keeps measuring the hash index.

| scenario | T7 ops/s | T8 ops/s | change | p50 µs (T7 → T8) | alloc KB/op (T7 → T8) |
|---|---:|---:|---:|---:|---:|
| point_lookup_index (B+Tree) | 12,131 | 75,966 | **6.3×** | 70 → 10 | 281 → 35 |
| point_lookup_hash | 12,131 | 11,084 | ≈ 1× | 70 → 75 | 281 → 281 |
| range_query (100 keys) | 171 | 7,979 | **47×** | 5,762 → 131 | 15,789 → 531 |
| mixed_80read_20write t1 | 3,405 | 4,354 | 1.3× | 76 → 9 | 234 → 37 |
| mixed_80read_20write t8 | 4,170 | 5,052 | 1.2× | 81 → 12 | 247 → 37 |

JMH (`bench-results/t8-btree/jmh-access-path.txt`), all pages cached:

| N | hash lookup | B+Tree lookup | B+Tree 100-key range |
|---:|---:|---:|---:|
| 1,000 | 4.1 µs | 2.0 µs | 3.5 µs |
| 10,000 | 35 µs | 2.1 µs | 4.6 µs |
| 100,000 | 388 µs | 2.8 µs | 4.4 µs |

Interpretation:

- **Logarithmic vs. linear.** The B+Tree's cost barely moves across two orders of magnitude of N
  (a root-to-leaf path of 2–3 pages); the hash index's grows with N because its bucket count
  is fixed at 16. That comparison is about *fixed* hashing, not hashing as such: a resizing
  hash index (linear or extendible hashing) would also be O(1) and would likely edge out the
  B+Tree for pure equality lookups. The B+Tree is the default because it also serves ranges.
- **Range queries stop being full scans**: a 100-key range reads one or two leaves.
- **Write-path scenarios didn't change.** The first T8 run showed fsync-bound scenarios 10–30%
  below T7; an interleaved A/B rerun of both builds showed the gap was machine state, not code
  (T7 itself measured ~20% below its own earlier numbers). See the noise floor note below.

### Noise floor

`concurrent_insert_own_table_t1` and `concurrent_insert_same_table_t1` are the *same* workload
(one thread, one table). In one T8 run they measured 893 and 1,186 ops/s. So differences of
up to about ±25% in fsync-bound scenarios on this laptop are noise, and only larger effects,
or effects confirmed by an interleaved A/B run of both builds, should be read as real.
CPU-bound scenarios (lookups, scans) and the JMH numbers are far more stable (typically ±10%).
