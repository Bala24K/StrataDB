# StrataDB workload benchmark

```
label:      final-ab-baseline-r1
date:       2026-11-11T02:02:35.278914100+05:30[Asia/Calcutta]
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
| insert_autocommit | 753 | 732..845 | 1,243 | 1,817 | 2,680 | 16,690 | 2.03 | 4,190 | 1.000 | 0 | 98.2 | 1 |
| insert_batched | 10,456 | 10,093..10,832 | 38,430 | 92,863 | 103,002 | 103,002 | 0.04 | 74 | 1.000 | 0 | 660.0 | 21 |
| point_lookup_index | 9,243 | 8,655..11,641 | 103 | 202 | 259 | 1,574 | 0.00 | 0 | 1.000 | 0 | 281.0 | 4 |
| range_query | 148 | 131..154 | 6,821 | 10,356 | 11,787 | 12,280 | 0.00 | 0 | 1.000 | 0 | 15,788.5 | 2 |
| full_scan | 3,714,469 | 3,034,685..4,352,974 | 5,200 | 7,171 | 7,171 | 7,171 | 0.00 | 0 | 1.000 | 0 | 0.8 | 0 |
| update_indexed | 225 | 203..228 | 4,214 | 6,355 | 7,447 | 10,176 | 4.99 | 16,330 | 1.000 | 0 | 1,933.9 | 3 |
| concurrent_insert_own_table_t8 | 691 | 691..718 | 10,533 | 21,548 | 24,670 | 27,265 | 2.03 | 4,179 | 1.000 | 0 | 48.3 | 0 |
| concurrent_insert_same_table_t8 | 523 | 517..552 | 3,398 | 29,560 | 234,634 | 1,451,434 | 2.03 | 4,189 | 1.000 | 25,977 | 110.0 | 0 |
| mixed_80read_20write_t8 | 1,640 | 1,487..1,872 | 111 | 24,364 | 85,557 | 300,969 | 0.60 | 1,629 | 1.000 | 64,137 | 532.8 | 13 |

Parameters:

- `insert_autocommit`: rows=2000
- `insert_batched`: rows=20000 batch=500 (latency per txn)
- `point_lookup_index`: table=20000 lookups=10000
- `range_query`: table=20000 width=100 queries=100
- `full_scan`: table=20000 scans=10 (ops = rows scanned)
- `update_indexed`: table=20000 updates=1000 autocommit
- `concurrent_insert_own_table_t8`: threads=8 inserts/thread=300
- `concurrent_insert_same_table_t8`: threads=8 inserts/thread=300
- `mixed_80read_20write_t8`: threads=8 ops/thread=2000 table=20000
