# StrataDB workload benchmark

```
label:      final-ab-baseline-r2
date:       2026-11-11T02:05:19.202344600+05:30[Asia/Calcutta]
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
| insert_autocommit | 487 | 472..496 | 1,858 | 3,318 | 4,077 | 7,576 | 2.03 | 4,190 | 1.000 | 0 | 97.8 | 1 |
| insert_batched | 7,419 | 6,490..7,955 | 63,408 | 106,319 | 108,796 | 108,796 | 0.04 | 74 | 1.000 | 0 | 660.0 | 21 |
| point_lookup_index | 9,272 | 7,755..9,594 | 96 | 213 | 274 | 2,153 | 0.00 | 0 | 1.000 | 0 | 281.0 | 4 |
| range_query | 169 | 137..189 | 5,262 | 9,087 | 10,073 | 10,097 | 0.00 | 0 | 1.000 | 0 | 15,788.6 | 2 |
| full_scan | 2,969,170 | 2,492,432..3,098,292 | 6,641 | 10,346 | 10,346 | 10,346 | 0.00 | 0 | 1.000 | 0 | 0.8 | 0 |
| update_indexed | 286 | 282..287 | 3,379 | 4,448 | 5,162 | 8,116 | 4.99 | 16,330 | 1.000 | 0 | 1,933.8 | 3 |
| concurrent_insert_own_table_t8 | 797 | 570..989 | 8,902 | 22,266 | 28,066 | 36,640 | 2.03 | 4,179 | 1.000 | 0 | 48.2 | 0 |
| concurrent_insert_same_table_t8 | 700 | 643..726 | 2,669 | 15,098 | 178,636 | 1,826,075 | 2.03 | 4,189 | 1.000 | 19,673 | 110.0 | 0 |
| mixed_80read_20write_t8 | 1,942 | 1,695..2,013 | 117 | 22,472 | 71,045 | 279,128 | 0.60 | 1,629 | 1.000 | 54,678 | 532.8 | 13 |

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
