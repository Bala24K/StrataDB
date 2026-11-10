# StrataDB workload benchmark

```
label:      t8-btree
date:       2026-11-11T01:40:52.828760500+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,568 | 1,403..1,616 | 618 | 818 | 984 | 5,440 | 1.00 | 4,202 | 1.000 | 0 | 27.1 | 1 |
| insert_batched | 142,907 | 122,643..157,719 | 3,575 | 4,394 | 6,072 | 6,072 | 0.00 | 74 | 1.000 | 0 | 7.3 | 1 |
| point_lookup_index | 75,966 | 56,085..103,289 | 10 | 23 | 48 | 251 | 0.00 | 0 | 1.000 | 0 | 35.3 | 0 |
| point_lookup_hash | 11,084 | 9,933..11,519 | 75 | 176 | 236 | 1,982 | 0.00 | 0 | 1.000 | 0 | 281.4 | 4 |
| point_lookup_scan | 178 | 160..199 | 5,584 | 8,042 | 10,209 | 11,732 | 0.00 | 0 | 1.000 | 0 | 15,307.4 | 2 |
| range_query | 7,979 | 6,167..8,301 | 131 | 224 | 255 | 317 | 0.00 | 0 | 1.000 | 0 | 531.1 | 0 |
| full_scan | 3,358,917 | 3,129,273..3,587,257 | 6,117 | 6,942 | 6,942 | 6,942 | 0.00 | 0 | 1.000 | 0 | 0.8 | 0 |
| update_indexed | 1,020 | 977..1,079 | 796 | 2,162 | 2,856 | 6,111 | 1.00 | 4,136 | 1.000 | 0 | 48.1 | 0 |
| concurrent_insert_own_table_t1 | 989 | 983..1,001 | 869 | 2,060 | 2,454 | 7,509 | 1.00 | 4,191 | 1.000 | 0 | 24.5 | 0 |
| concurrent_insert_own_table_t2 | 1,148 | 1,119..1,156 | 1,690 | 3,115 | 3,754 | 4,272 | 0.88 | 4,191 | 1.000 | 0 | 25.6 | 0 |
| concurrent_insert_own_table_t4 | 1,951 | 1,950..1,986 | 1,888 | 3,610 | 4,490 | 6,247 | 0.47 | 4,191 | 1.000 | 0 | 27.6 | 0 |
| concurrent_insert_own_table_t8 | 3,125 | 3,021..3,142 | 2,360 | 4,549 | 5,082 | 7,605 | 0.26 | 4,191 | 1.000 | 0 | 31.5 | 0 |
| concurrent_insert_same_table_t1 | 959 | 885..976 | 924 | 2,261 | 2,474 | 2,809 | 1.00 | 4,191 | 1.000 | 0 | 24.5 | 0 |
| concurrent_insert_same_table_t2 | 914 | 877..946 | 1,273 | 6,004 | 13,567 | 22,231 | 1.00 | 4,198 | 1.000 | 567 | 24.9 | 0 |
| concurrent_insert_same_table_t4 | 840 | 827..852 | 1,589 | 9,770 | 71,081 | 250,213 | 1.00 | 4,201 | 1.000 | 3,886 | 25.2 | 0 |
| concurrent_insert_same_table_t8 | 845 | 827..851 | 1,039 | 11,779 | 140,415 | 1,609,818 | 1.00 | 4,201 | 1.000 | 14,264 | 25.3 | 0 |
| mixed_80read_20write_t1 | 4,354 | 3,452..4,682 | 9 | 1,119 | 2,399 | 4,718 | 0.20 | 1,707 | 1.000 | 0 | 37.1 | 0 |
| mixed_80read_20write_t2 | 3,640 | 3,484..4,761 | 15 | 2,674 | 5,744 | 12,492 | 0.20 | 1,642 | 1.000 | 1,037 | 36.8 | 0 |
| mixed_80read_20write_t4 | 3,657 | 3,548..4,402 | 13 | 5,844 | 14,139 | 37,942 | 0.20 | 1,663 | 1.000 | 6,406 | 36.8 | 0 |
| mixed_80read_20write_t8 | 5,052 | 5,030..5,052 | 12 | 7,829 | 30,817 | 98,077 | 0.20 | 1,638 | 1.000 | 21,131 | 36.8 | 0 |

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
