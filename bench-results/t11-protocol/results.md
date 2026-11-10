# StrataDB workload benchmark

```
label:      scratch-net
date:       2026-11-11T01:57:39.024001900+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,458 | 1,310..1,485 | 650 | 918 | 1,142 | 4,431 | 1.00 | 4,202 | 1.000 | 0 | 27.8 | 1 |
| point_lookup_index | 62,482 | 62,083..69,116 | 12 | 28 | 52 | 974 | 0.00 | 0 | 1.000 | 0 | 35.9 | 1 |
| net_point_lookup_index | 24,775 | 13,246..25,518 | 29 | 88 | 134 | 837 | 0.00 | 0 | 1.000 | 0 | 37.0 | 0 |
| net_insert_autocommit | 1,228 | 1,190..1,320 | 786 | 1,131 | 1,348 | 6,154 | 1.00 | 4,202 | 1.000 | 0 | 26.9 | 0 |

Parameters:

- `insert_autocommit`: rows=2000
- `point_lookup_index`: table=20000 lookups=10000
- `net_point_lookup_index`: table=20000 lookups=10000 via localhost TCP
- `net_insert_autocommit`: rows=2000 via localhost TCP
