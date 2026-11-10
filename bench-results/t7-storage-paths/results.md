# StrataDB workload benchmark

```
label:      t7-storage-paths
date:       2026-11-11T01:31:01.319108+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,566 | 1,457..1,635 | 612 | 830 | 1,041 | 11,723 | 1.00 | 4,202 | 1.000 | 0 | 27.1 | 1 |
| insert_batched | 178,584 | 130,464..183,552 | 2,726 | 3,892 | 4,054 | 4,054 | 0.00 | 74 | 1.000 | 0 | 7.2 | 1 |
| point_lookup_index | 12,131 | 8,717..12,701 | 70 | 153 | 202 | 2,346 | 0.00 | 0 | 1.000 | 0 | 281.0 | 4 |
| point_lookup_scan | 194 | 184..195 | 4,552 | 8,579 | 9,339 | 11,149 | 0.00 | 0 | 1.000 | 0 | 15,306.9 | 2 |
| range_query | 171 | 171..195 | 5,762 | 9,633 | 11,155 | 11,204 | 0.00 | 0 | 1.000 | 0 | 15,788.6 | 2 |
| full_scan | 3,488,222 | 3,186,901..4,264,120 | 6,081 | 7,951 | 7,951 | 7,951 | 0.00 | 0 | 1.000 | 0 | 0.8 | 0 |
| update_indexed | 1,352 | 1,264..1,362 | 695 | 1,096 | 1,462 | 1,887 | 1.00 | 4,136 | 1.000 | 0 | 295.0 | 0 |
| concurrent_insert_own_table_t1 | 1,409 | 1,406..1,433 | 682 | 990 | 1,192 | 2,043 | 1.00 | 4,191 | 1.000 | 0 | 24.4 | 0 |
| concurrent_insert_own_table_t2 | 1,569 | 1,520..1,612 | 1,295 | 1,823 | 2,023 | 2,156 | 0.90 | 4,191 | 1.000 | 0 | 25.6 | 0 |
| concurrent_insert_own_table_t4 | 2,561 | 2,472..2,685 | 1,563 | 2,400 | 2,745 | 3,503 | 0.48 | 4,191 | 1.000 | 0 | 27.5 | 0 |
| concurrent_insert_own_table_t8 | 4,548 | 4,291..4,665 | 1,680 | 2,879 | 3,297 | 3,803 | 0.26 | 4,191 | 1.000 | 0 | 31.4 | 0 |
| concurrent_insert_same_table_t1 | 1,380 | 1,378..1,386 | 694 | 971 | 1,148 | 1,358 | 1.00 | 4,191 | 1.000 | 0 | 24.4 | 0 |
| concurrent_insert_same_table_t2 | 1,457 | 1,392..1,475 | 1,084 | 3,790 | 5,923 | 8,317 | 1.00 | 4,198 | 1.000 | 392 | 24.8 | 0 |
| concurrent_insert_same_table_t4 | 1,451 | 1,426..1,493 | 820 | 4,816 | 35,802 | 132,248 | 1.00 | 4,201 | 1.000 | 1,975 | 25.1 | 0 |
| concurrent_insert_same_table_t8 | 1,388 | 975..1,515 | 742 | 5,990 | 74,657 | 1,046,499 | 1.00 | 4,201 | 1.000 | 9,394 | 25.2 | 0 |
| mixed_80read_20write_t1 | 3,405 | 3,016..3,544 | 76 | 1,122 | 2,452 | 4,900 | 0.20 | 1,694 | 1.000 | 0 | 234.2 | 0 |
| mixed_80read_20write_t2 | 4,113 | 3,784..4,233 | 74 | 2,083 | 5,163 | 25,851 | 0.20 | 1,632 | 1.000 | 898 | 237.2 | 1 |
| mixed_80read_20write_t4 | 4,283 | 4,166..4,289 | 67 | 3,844 | 14,824 | 48,793 | 0.20 | 1,651 | 1.000 | 5,197 | 239.7 | 3 |
| mixed_80read_20write_t8 | 4,170 | 4,056..4,237 | 81 | 5,953 | 36,746 | 151,290 | 0.20 | 1,631 | 1.000 | 21,691 | 246.8 | 6 |

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
