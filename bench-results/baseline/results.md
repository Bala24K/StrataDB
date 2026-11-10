# StrataDB workload benchmark

```
label:      baseline
date:       2026-11-11T01:12:48.672864+05:30[Asia/Calcutta]
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
| insert_autocommit | 495 | 449..707 | 1,771 | 3,521 | 4,789 | 8,588 | 2.03 | 4,190 | 1.000 | 0 | 97.7 | 1 |
| insert_batched | 9,571 | 9,507..9,650 | 48,512 | 84,372 | 93,499 | 93,499 | 0.04 | 74 | 1.000 | 0 | 662.6 | 23 |
| point_lookup_index | 11,065 | 9,998..12,240 | 95 | 149 | 192 | 1,869 | 0.00 | 0 | 1.000 | 0 | 280.9 | 4 |
| point_lookup_scan | 181 | 167..185 | 5,264 | 8,342 | 9,506 | 10,458 | 0.00 | 0 | 1.000 | 0 | 15,306.9 | 2 |
| range_query | 180 | 162..185 | 5,045 | 8,521 | 9,202 | 10,014 | 0.00 | 0 | 1.000 | 0 | 15,788.6 | 2 |
| full_scan | 3,551,029 | 2,793,526..3,900,399 | 5,668 | 6,817 | 6,817 | 6,817 | 0.00 | 0 | 1.000 | 0 | 0.8 | 0 |
| update_indexed | 213 | 206..214 | 4,473 | 6,638 | 7,782 | 34,953 | 4.99 | 16,330 | 1.000 | 0 | 1,933.9 | 3 |
| concurrent_insert_own_table_t1 | 550 | 533..561 | 1,646 | 2,962 | 3,263 | 6,614 | 2.03 | 4,179 | 1.000 | 0 | 41.5 | 0 |
| concurrent_insert_own_table_t2 | 668 | 641..672 | 2,771 | 4,879 | 5,737 | 10,401 | 2.03 | 4,179 | 1.000 | 0 | 42.4 | 0 |
| concurrent_insert_own_table_t4 | 694 | 693..704 | 5,420 | 9,863 | 11,762 | 14,342 | 2.03 | 4,179 | 1.000 | 0 | 44.4 | 0 |
| concurrent_insert_own_table_t8 | 709 | 705..709 | 10,587 | 21,115 | 23,802 | 27,893 | 2.03 | 4,179 | 1.000 | 0 | 48.2 | 0 |
| concurrent_insert_same_table_t1 | 559 | 558..559 | 1,630 | 3,066 | 3,657 | 3,760 | 2.03 | 4,179 | 1.000 | 0 | 41.4 | 0 |
| concurrent_insert_same_table_t2 | 562 | 519..573 | 2,092 | 10,078 | 16,646 | 30,113 | 2.03 | 4,186 | 1.000 | 962 | 51.2 | 0 |
| concurrent_insert_same_table_t4 | 555 | 546..557 | 2,701 | 14,524 | 103,101 | 288,336 | 2.03 | 4,189 | 1.000 | 5,918 | 70.8 | 0 |
| concurrent_insert_same_table_t8 | 729 | 563..736 | 1,411 | 12,230 | 145,832 | 2,168,814 | 2.03 | 4,189 | 1.000 | 17,298 | 110.0 | 0 |
| mixed_80read_20write_t1 | 1,944 | 1,877..2,121 | 76 | 2,334 | 3,082 | 7,079 | 0.62 | 1,692 | 1.000 | 0 | 513.5 | 1 |
| mixed_80read_20write_t2 | 2,057 | 2,044..2,092 | 74 | 4,382 | 8,173 | 26,219 | 0.60 | 1,629 | 1.000 | 1,768 | 508.7 | 3 |
| mixed_80read_20write_t4 | 1,617 | 1,558..1,651 | 82 | 10,908 | 35,254 | 111,649 | 0.60 | 1,649 | 1.000 | 13,566 | 519.7 | 6 |
| mixed_80read_20write_t8 | 1,595 | 1,595..1,778 | 93 | 27,053 | 84,628 | 282,806 | 0.60 | 1,629 | 1.000 | 65,536 | 532.9 | 13 |

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
