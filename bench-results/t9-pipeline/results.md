# StrataDB workload benchmark

```
label:      t9-pipeline
date:       2026-11-11T01:48:41.264883800+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,526 | 1,432..1,634 | 628 | 855 | 1,024 | 5,275 | 1.00 | 4,202 | 1.000 | 0 | 27.1 | 1 |
| insert_batched | 162,525 | 137,959..176,534 | 3,248 | 3,897 | 4,130 | 4,130 | 0.00 | 74 | 1.000 | 0 | 7.2 | 1 |
| point_lookup_index | 69,238 | 53,721..104,084 | 12 | 23 | 48 | 332 | 0.00 | 0 | 1.000 | 0 | 35.1 | 0 |
| point_lookup_hash | 12,042 | 9,694..12,303 | 66 | 161 | 204 | 1,560 | 0.00 | 0 | 1.000 | 0 | 281.1 | 4 |
| point_lookup_scan | 543 | 468..555 | 1,503 | 3,688 | 3,958 | 4,339 | 0.00 | 0 | 1.000 | 0 | 5,997.6 | 0 |
| range_query | 16,590 | 10,658..23,285 | 56 | 111 | 236 | 240 | 0.00 | 0 | 1.000 | 0 | 116.2 | 0 |
| full_scan | 12,639,189 | 10,128,018..14,872,100 | 1,265 | 4,136 | 4,136 | 4,136 | 0.00 | 0 | 1.000 | 0 | 0.3 | 0 |
| update_indexed | 1,426 | 1,349..1,454 | 664 | 1,000 | 1,172 | 5,581 | 1.00 | 4,136 | 1.000 | 0 | 47.9 | 0 |
| concurrent_insert_own_table_t1 | 1,317 | 1,241..1,385 | 727 | 1,050 | 1,275 | 1,390 | 1.00 | 4,191 | 1.000 | 0 | 24.5 | 0 |
| concurrent_insert_own_table_t2 | 1,464 | 1,392..1,510 | 1,393 | 1,784 | 1,990 | 2,497 | 0.94 | 4,191 | 1.000 | 0 | 25.6 | 0 |
| concurrent_insert_own_table_t4 | 2,635 | 2,530..2,738 | 1,432 | 2,448 | 3,318 | 6,860 | 0.48 | 4,191 | 1.000 | 0 | 27.6 | 0 |
| concurrent_insert_own_table_t8 | 4,077 | 4,006..4,274 | 1,771 | 3,438 | 5,155 | 7,955 | 0.26 | 4,191 | 1.000 | 0 | 31.5 | 0 |
| concurrent_insert_same_table_t1 | 905 | 899..955 | 959 | 2,112 | 2,990 | 3,492 | 1.00 | 4,191 | 1.000 | 0 | 24.5 | 0 |
| concurrent_insert_same_table_t2 | 1,206 | 1,072..1,261 | 1,539 | 3,243 | 6,520 | 7,688 | 1.00 | 4,198 | 1.000 | 490 | 24.9 | 0 |
| concurrent_insert_same_table_t4 | 1,431 | 1,265..1,449 | 879 | 5,610 | 52,105 | 112,127 | 1.00 | 4,201 | 1.000 | 2,319 | 25.2 | 0 |
| concurrent_insert_same_table_t8 | 1,419 | 1,404..1,460 | 718 | 6,760 | 75,443 | 783,073 | 1.00 | 4,201 | 1.000 | 8,938 | 25.3 | 0 |
| mixed_80read_20write_t1 | 5,406 | 4,900..5,636 | 17 | 904 | 1,170 | 4,413 | 0.20 | 1,707 | 1.000 | 0 | 36.7 | 0 |
| mixed_80read_20write_t2 | 6,475 | 5,927..6,687 | 13 | 1,314 | 2,776 | 7,279 | 0.20 | 1,642 | 1.000 | 557 | 36.5 | 0 |
| mixed_80read_20write_t4 | 6,568 | 4,814..6,646 | 13 | 3,122 | 7,809 | 21,562 | 0.20 | 1,663 | 1.000 | 3,543 | 36.4 | 0 |
| mixed_80read_20write_t8 | 4,981 | 3,987..5,062 | 13 | 8,029 | 31,252 | 154,631 | 0.20 | 1,638 | 1.000 | 21,510 | 36.4 | 0 |

Parameters:

- `insert_autocommit`: rows=2000
- `insert_batched`: rows=20000 batch=500 (latency per txn)
- `point_lookup_index`: table=20000 lookups=10000
- `point_lookup_hash`: table=20000 lookups=10000
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
