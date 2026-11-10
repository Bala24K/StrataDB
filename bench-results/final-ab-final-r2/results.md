# StrataDB workload benchmark

```
label:      final-ab-final-r2
date:       2026-11-11T02:05:51.866875700+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,413 | 1,410..1,575 | 660 | 1,026 | 1,401 | 5,957 | 1.00 | 4,202 | 1.000 | 0 | 27.1 | 0 |
| insert_batched | 122,084 | 119,791..133,638 | 3,990 | 6,632 | 7,505 | 7,505 | 0.00 | 74 | 1.000 | 0 | 7.5 | 1 |
| point_lookup_index | 85,372 | 52,876..127,653 | 9 | 19 | 41 | 339 | 0.00 | 0 | 1.000 | 0 | 35.4 | 0 |
| range_query | 14,646 | 14,016..26,818 | 52 | 128 | 345 | 373 | 0.00 | 0 | 1.000 | 0 | 116.8 | 0 |
| full_scan | 8,354,603 | 3,645,982..10,057,984 | 1,870 | 4,100 | 4,100 | 4,100 | 0.00 | 0 | 1.000 | 0 | 0.3 | 0 |
| update_indexed | 1,040 | 1,039..1,050 | 844 | 2,050 | 2,354 | 4,511 | 1.00 | 4,136 | 1.000 | 0 | 48.5 | 0 |
| concurrent_insert_own_table_t8 | 3,156 | 3,133..3,255 | 2,338 | 4,386 | 5,029 | 6,809 | 0.26 | 4,191 | 1.000 | 0 | 31.9 | 0 |
| concurrent_insert_same_table_t8 | 1,044 | 1,037..1,049 | 7,600 | 10,204 | 11,753 | 12,946 | 1.00 | 4,201 | 1.000 | 16,052 | 28.5 | 0 |
| mixed_80read_20write_t8 | 4,696 | 4,646..4,706 | 1,461 | 4,213 | 5,405 | 11,593 | 0.20 | 1,638 | 1.000 | 23,307 | 38.4 | 1 |

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
