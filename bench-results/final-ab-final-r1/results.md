# StrataDB workload benchmark

```
label:      final-ab-final-r1
date:       2026-11-11T02:03:08.725739900+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,462 | 1,460..1,590 | 656 | 916 | 1,085 | 5,357 | 1.00 | 4,202 | 1.000 | 0 | 27.8 | 1 |
| insert_batched | 158,649 | 120,686..166,594 | 3,202 | 3,885 | 4,531 | 4,531 | 0.00 | 74 | 1.000 | 0 | 7.2 | 1 |
| point_lookup_index | 62,561 | 48,387..66,185 | 12 | 27 | 57 | 380 | 0.00 | 0 | 1.000 | 0 | 36.4 | 0 |
| range_query | 12,868 | 7,969..15,742 | 69 | 112 | 298 | 332 | 0.00 | 0 | 1.000 | 0 | 116.8 | 0 |
| full_scan | 5,403,945 | 3,191,401..14,597,368 | 3,465 | 5,363 | 5,363 | 5,363 | 0.00 | 0 | 1.000 | 0 | 0.3 | 0 |
| update_indexed | 1,335 | 1,315..1,459 | 722 | 1,024 | 1,194 | 1,443 | 1.00 | 4,136 | 1.000 | 0 | 48.5 | 0 |
| concurrent_insert_own_table_t8 | 3,228 | 3,200..3,257 | 2,267 | 4,295 | 4,949 | 6,487 | 0.26 | 4,191 | 1.000 | 0 | 31.9 | 0 |
| concurrent_insert_same_table_t8 | 1,035 | 950..1,066 | 7,648 | 10,012 | 11,620 | 12,769 | 1.00 | 4,201 | 1.000 | 16,193 | 28.6 | 0 |
| mixed_80read_20write_t8 | 3,998 | 3,853..4,014 | 1,796 | 4,607 | 5,922 | 15,749 | 0.20 | 1,639 | 1.000 | 27,443 | 38.5 | 1 |

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
