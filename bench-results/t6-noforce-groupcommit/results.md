# StrataDB workload benchmark

```
label:      t6-noforce-groupcommit
date:       2026-11-11T01:23:21.055236900+05:30[Asia/Calcutta]
scale:      Scale[name=medium, autocommitRows=2000, bulkRows=20000, batchSize=500, lookups=10000, scanLookups=100, ranges=100, fullScans=10, updates=1000, perThreadInserts=300, mixedOpsPerThread=2000]
runs:       3 measured (+1 warm-up at 1/10 size)
java:       Java HotSpot(TM) 64-Bit Server VM 23.0.1+11-39
jvm args:   [-Xms1g, -Xmx1g]
gc:         G1 Young Generation, G1 Concurrent GC, G1 Old Generation
os:         Windows 11 10.0 (amd64)
cpus:       16
max heap:   1024 MB
temp dir:   C:\Users\Balaa\AppData\Local\Temp\
```

Median of runs by throughput; spread = min..max throughput across runs. Latency in microseconds.

| scenario | ops/s | spread | p50 µs | p95 µs | p99 µs | max µs | fsyncs/op | WAL B/op | hit ratio | lock wait ms | alloc KB/op | GCs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| insert_autocommit | 1,382 | 1,326..1,556 | 684 | 1,037 | 1,272 | 5,353 | 1.00 | 4,202 | 1.000 | 0 | 88.7 | 2 |
| insert_batched | 12,189 | 10,198..12,239 | 45,083 | 74,972 | 83,614 | 83,614 | 0.00 | 74 | 1.000 | 0 | 659.6 | 21 |
| point_lookup_index | 10,975 | 8,609..11,111 | 78 | 172 | 232 | 1,656 | 0.00 | 0 | 1.000 | 0 | 281.0 | 4 |
| point_lookup_scan | 186 | 182..197 | 4,966 | 7,792 | 9,162 | 9,630 | 0.00 | 0 | 1.000 | 0 | 15,306.9 | 2 |
| range_query | 163 | 159..181 | 5,784 | 9,578 | 12,169 | 13,246 | 0.00 | 0 | 1.000 | 0 | 15,788.5 | 2 |
| full_scan | 3,772,930 | 3,108,520..4,152,850 | 4,318 | 8,445 | 8,445 | 8,445 | 0.00 | 0 | 1.000 | 0 | 0.8 | 0 |
| update_indexed | 881 | 797..896 | 1,052 | 1,703 | 2,051 | 9,549 | 1.00 | 16,342 | 1.000 | 0 | 1,892.7 | 3 |
| concurrent_insert_own_table_t1 | 1,319 | 1,317..1,373 | 704 | 1,145 | 1,361 | 1,462 | 1.00 | 4,191 | 1.000 | 0 | 29.0 | 0 |
| concurrent_insert_own_table_t2 | 1,575 | 1,454..1,589 | 1,275 | 1,884 | 2,071 | 2,401 | 0.88 | 4,191 | 1.000 | 0 | 30.0 | 0 |
| concurrent_insert_own_table_t4 | 2,595 | 2,535..2,669 | 1,496 | 2,347 | 2,858 | 6,930 | 0.48 | 4,191 | 1.000 | 0 | 32.0 | 0 |
| concurrent_insert_own_table_t8 | 4,215 | 4,109..4,301 | 1,841 | 3,057 | 3,559 | 4,357 | 0.26 | 4,191 | 1.000 | 0 | 35.9 | 0 |
| concurrent_insert_same_table_t1 | 1,250 | 1,216..1,329 | 753 | 1,156 | 1,368 | 1,972 | 1.00 | 4,191 | 1.000 | 0 | 28.9 | 0 |
| concurrent_insert_same_table_t2 | 1,404 | 943..1,424 | 830 | 4,231 | 8,638 | 13,066 | 1.00 | 4,198 | 1.000 | 418 | 38.7 | 0 |
| concurrent_insert_same_table_t4 | 1,212 | 1,008..1,219 | 1,214 | 6,767 | 60,161 | 155,202 | 1.00 | 4,201 | 1.000 | 2,708 | 58.3 | 0 |
| concurrent_insert_same_table_t8 | 1,430 | 1,126..1,441 | 699 | 6,503 | 74,406 | 912,672 | 1.00 | 4,201 | 1.000 | 8,415 | 97.5 | 0 |
| mixed_80read_20write_t1 | 3,762 | 3,611..3,923 | 100 | 1,050 | 1,347 | 2,584 | 0.20 | 1,694 | 1.000 | 0 | 507.6 | 1 |
| mixed_80read_20write_t2 | 4,341 | 4,108..4,367 | 89 | 1,748 | 4,510 | 14,880 | 0.20 | 1,632 | 1.000 | 782 | 503.1 | 3 |
| mixed_80read_20write_t4 | 4,194 | 4,000..4,340 | 101 | 3,604 | 14,427 | 67,241 | 0.20 | 1,651 | 1.000 | 5,222 | 514.0 | 6 |
| mixed_80read_20write_t8 | 3,330 | 3,174..3,432 | 116 | 7,476 | 43,895 | 175,947 | 0.20 | 1,631 | 1.000 | 26,823 | 527.2 | 13 |

Parameters:

- `insert_autocommit`: rows=2000
- `insert_batched`: rows=20000 batch=500 (latency per txn)
- `point_lookup_index`: table=20000 lookups=10000
- `point_lookup_scan`: table=20000 lookups=100
- `range_query`: table=20000 width=100 queries=100
- `full_scan`: table=20000 scans=10 (ops = rows scanned)
- `update_indexed`: table=20000 updates=1000 autocommit
- `concurrent_insert_own_table_t1`: threads=1 inserts/thread=300
- `concurrent_insert_own_table_t2`: threads=2 inserts/thread=300
- `concurrent_insert_own_table_t4`: threads=4 inserts/thread=300
- `concurrent_insert_own_table_t8`: threads=8 inserts/thread=300
- `concurrent_insert_same_table_t1`: threads=1 inserts/thread=300
- `concurrent_insert_same_table_t2`: threads=2 inserts/thread=300
- `concurrent_insert_same_table_t4`: threads=4 inserts/thread=300
- `concurrent_insert_same_table_t8`: threads=8 inserts/thread=300
- `mixed_80read_20write_t1`: threads=1 ops/thread=2000 table=20000
- `mixed_80read_20write_t2`: threads=2 ops/thread=2000 table=20000
- `mixed_80read_20write_t4`: threads=4 ops/thread=2000 table=20000
- `mixed_80read_20write_t8`: threads=8 ops/thread=2000 table=20000
