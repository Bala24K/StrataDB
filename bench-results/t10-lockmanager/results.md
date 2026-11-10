# StrataDB workload benchmark

```
label:      t10-lockmanager
date:       2026-11-11T01:53:38.645875600+05:30[Asia/Calcutta]
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
| insert_autocommit | 1,062 | 1,017..1,083 | 819 | 2,111 | 2,452 | 3,533 | 1.00 | 4,202 | 1.000 | 0 | 27.2 | 0 |
| insert_batched | 135,565 | 97,299..147,024 | 3,579 | 5,628 | 6,661 | 6,661 | 0.00 | 74 | 1.000 | 0 | 7.3 | 1 |
| point_lookup_index | 66,919 | 47,095..103,053 | 12 | 25 | 52 | 475 | 0.00 | 0 | 1.000 | 0 | 35.7 | 0 |
| point_lookup_hash | 9,245 | 8,044..9,311 | 97 | 210 | 269 | 1,854 | 0.00 | 0 | 1.000 | 0 | 281.8 | 4 |
| point_lookup_scan | 391 | 386..412 | 2,557 | 5,688 | 5,845 | 6,204 | 0.00 | 0 | 1.000 | 0 | 5,998.4 | 0 |
| range_query | 11,828 | 8,656..26,975 | 73 | 109 | 282 | 328 | 0.00 | 0 | 1.000 | 0 | 116.9 | 0 |
| full_scan | 8,362,987 | 7,998,176..12,494,768 | 2,262 | 3,195 | 3,195 | 3,195 | 0.00 | 0 | 1.000 | 0 | 0.3 | 0 |
| update_indexed | 1,166 | 1,053..1,178 | 716 | 1,989 | 2,517 | 3,649 | 1.00 | 4,136 | 1.000 | 0 | 48.7 | 0 |
| concurrent_insert_own_table_t1 | 1,036 | 989..1,050 | 871 | 1,943 | 2,166 | 2,624 | 1.00 | 4,191 | 1.000 | 0 | 25.2 | 0 |
| concurrent_insert_own_table_t2 | 1,144 | 1,067..1,189 | 1,700 | 3,089 | 3,392 | 4,184 | 0.89 | 4,191 | 1.000 | 0 | 26.1 | 0 |
| concurrent_insert_own_table_t4 | 1,979 | 1,953..2,048 | 1,831 | 3,629 | 4,274 | 5,436 | 0.48 | 4,191 | 1.000 | 0 | 28.0 | 0 |
| concurrent_insert_own_table_t8 | 3,233 | 3,120..3,233 | 2,270 | 4,361 | 5,476 | 8,113 | 0.26 | 4,191 | 1.000 | 0 | 31.9 | 0 |
| concurrent_insert_same_table_t1 | 1,029 | 960..1,034 | 867 | 2,062 | 2,420 | 2,643 | 1.00 | 4,191 | 1.000 | 0 | 25.2 | 0 |
| concurrent_insert_same_table_t2 | 1,068 | 1,066..1,079 | 1,726 | 3,093 | 3,446 | 3,661 | 1.00 | 4,198 | 1.000 | 561 | 25.8 | 0 |
| concurrent_insert_same_table_t4 | 1,064 | 1,063..1,065 | 3,578 | 5,261 | 6,005 | 7,795 | 1.00 | 4,201 | 1.000 | 3,381 | 26.6 | 0 |
| concurrent_insert_same_table_t8 | 1,003 | 1,000..1,066 | 7,975 | 9,956 | 10,988 | 14,800 | 1.00 | 4,201 | 1.000 | 16,734 | 28.5 | 0 |
| mixed_80read_20write_t1 | 4,478 | 4,404..4,679 | 16 | 1,019 | 2,273 | 2,829 | 0.20 | 1,707 | 1.000 | 0 | 37.7 | 0 |
| mixed_80read_20write_t2 | 4,515 | 4,490..4,717 | 21 | 2,044 | 3,013 | 5,410 | 0.20 | 1,642 | 1.000 | 859 | 37.1 | 0 |
| mixed_80read_20write_t4 | 4,364 | 4,271..4,767 | 855 | 2,835 | 4,110 | 6,729 | 0.20 | 1,662 | 1.000 | 5,274 | 37.5 | 0 |
| mixed_80read_20write_t8 | 4,266 | 4,184..4,333 | 1,637 | 4,539 | 5,907 | 19,742 | 0.20 | 1,639 | 1.000 | 25,618 | 38.8 | 0 |

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
