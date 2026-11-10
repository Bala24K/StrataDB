package com.strata.bench;

import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.metrics.Metrics;
import com.strata.transaction.Session;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * End-to-end database workloads (as opposed to the JMH microbenchmarks in {@code micro}):
 * each scenario drives real SQL through {@link Session}s against a fresh database file on disk
 * and reports throughput, per-operation latency percentiles, engine metric deltas (fsyncs,
 * WAL bytes, buffer pool hit ratio, lock waits) and JVM allocation/GC cost.
 *
 * <pre>
 *   scripts/bench.sh workload [--scale small|medium|large] [--runs N] [--label NAME] [--only a,b]
 * </pre>
 *
 * Methodology (see docs/BENCHMARKS.md): every scenario first runs once at reduced size as JIT
 * warm-up, then {@code --runs} measured repetitions, each on a brand-new database file; the
 * report shows the median run by throughput, plus the min-max spread across runs.
 */
public final class WorkloadBenchmark {

    // ---- configuration ----

    record Scale(String name, int autocommitRows, int bulkRows, int batchSize, int lookups,
                 int scanLookups, int ranges, int fullScans, int updates, int perThreadInserts,
                 int mixedOpsPerThread) {
        Scale warmup() {
            return new Scale(name + "-warmup", Math.max(50, autocommitRows / 10), Math.max(500, bulkRows / 10),
                batchSize, Math.max(100, lookups / 10), Math.max(10, scanLookups / 10), Math.max(10, ranges / 10),
                2, Math.max(20, updates / 10), Math.max(20, perThreadInserts / 10), Math.max(50, mixedOpsPerThread / 10));
        }
    }

    static final Map<String, Scale> SCALES = Map.of(
        "small", new Scale("small", 500, 5_000, 500, 2_000, 50, 50, 5, 300, 100, 500),
        "medium", new Scale("medium", 2_000, 20_000, 500, 10_000, 100, 100, 10, 1_000, 300, 2_000),
        "large", new Scale("large", 5_000, 100_000, 1_000, 50_000, 100, 100, 10, 2_000, 500, 5_000));

    static final int[] THREADS = {1, 2, 4, 8};
    static final String PAD = "x".repeat(40); // ~60-byte rows: id INTEGER, val BIGINT, pad VARCHAR(40)

    // ---- results ----

    record Result(String scenario, String params, long ops, double seconds, LatencyRecorder.Summary latency,
                  Metrics metrics, long allocatedBytes, long gcCount, long gcMillis, long heapUsedAfter) {
        double opsPerSec() {
            return ops / seconds;
        }
    }

    private final Scale scale;
    private final int runs;
    private final Path workDir;
    private final Set<String> only;

    WorkloadBenchmark(Scale scale, int runs, Path workDir, Set<String> only) {
        this.scale = scale;
        this.runs = runs;
        this.workDir = workDir;
        this.only = only;
    }

    public static void main(String[] args) throws Exception {
        String scaleName = "small";
        int runs = 3;
        String label = "run";
        Set<String> only = Set.of();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scale" -> scaleName = args[++i];
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--label" -> label = args[++i];
                case "--only" -> only = Set.of(args[++i].split(","));
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        Scale scale = SCALES.get(scaleName);
        if (scale == null) throw new IllegalArgumentException("unknown scale " + scaleName + "; use " + SCALES.keySet());

        Path workDir = Files.createTempDirectory("stratadb-bench");
        Path outDir = Path.of("bench-results", label);
        Files.createDirectories(outDir);

        WorkloadBenchmark bench = new WorkloadBenchmark(scale, runs, workDir, only);
        List<List<Result>> allRuns = bench.runAll();
        String env = environment(scale, runs, label);
        String report = report(env, allRuns);
        System.out.println(report);
        Files.writeString(outDir.resolve("results.md"), report, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("results.csv"), csv(allRuns), StandardCharsets.UTF_8);
        System.out.println("Wrote " + outDir.toAbsolutePath());
        deleteRecursively(workDir);
    }

    // ---- driver ----

    interface Scenario {
        Result run(Scale s) throws Exception;
    }

    List<List<Result>> runAll() throws Exception {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("insert_autocommit", this::insertAutocommit);
        scenarios.put("insert_batched", this::insertBatched);
        scenarios.put("point_lookup_index", s -> pointLookup(s, true));
        scenarios.put("point_lookup_hash", this::pointLookupHash);
        scenarios.put("point_lookup_scan", s -> pointLookup(s, false));
        scenarios.put("range_query", this::rangeQuery);
        scenarios.put("full_scan", this::fullScan);
        scenarios.put("update_indexed", this::updateIndexed);
        for (int t : THREADS) scenarios.put("concurrent_insert_own_table_t" + t, s -> concurrentInsert(s, t, false));
        for (int t : THREADS) scenarios.put("concurrent_insert_same_table_t" + t, s -> concurrentInsert(s, t, true));
        for (int t : THREADS) scenarios.put("mixed_80read_20write_t" + t, s -> mixed(s, t));

        List<List<Result>> grouped = new ArrayList<>();
        for (Map.Entry<String, Scenario> e : scenarios.entrySet()) {
            if (!only.isEmpty() && only.stream().noneMatch(e.getKey()::startsWith)) continue;
            System.err.printf("%-36s warm-up...", e.getKey());
            e.getValue().run(scale.warmup());
            List<Result> results = new ArrayList<>();
            for (int r = 0; r < runs; r++) {
                results.add(e.getValue().run(scale));
                System.err.printf(" run%d=%.0f ops/s", r + 1, results.get(r).opsPerSec());
            }
            System.err.println();
            grouped.add(results);
        }
        return grouped;
    }

    // ---- scenarios ----

    /** One INSERT per transaction: the commit path (WAL fsync + page flush) dominates. */
    Result insertAutocommit(Scale s) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            session.execute("CREATE TABLE t (id INTEGER, val BIGINT, pad VARCHAR);");
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int i = 0; i < s.autocommitRows(); i++) {
                long t0 = System.nanoTime();
                session.execute(insertSql(i));
                lat.record(System.nanoTime() - t0);
            }
            return m.finish("insert_autocommit", "rows=" + s.autocommitRows(), s.autocommitRows(), lat);
        }
    }

    /** Many INSERTs per transaction: per-row cost without per-row commits. Latency is per transaction. */
    Result insertBatched(Scale s) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            session.execute("CREATE TABLE t (id INTEGER, val BIGINT, pad VARCHAR);");
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int base = 0; base < s.bulkRows(); base += s.batchSize()) {
                long t0 = System.nanoTime();
                session.execute("BEGIN;");
                for (int i = base; i < Math.min(s.bulkRows(), base + s.batchSize()); i++) {
                    session.execute(insertSql(i));
                }
                session.execute("COMMIT;");
                lat.record(System.nanoTime() - t0);
            }
            return m.finish("insert_batched", "rows=" + s.bulkRows() + " batch=" + s.batchSize() + " (latency per txn)",
                s.bulkRows(), lat);
        }
    }

    Result pointLookup(Scale s, boolean indexed) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            load(session, s.bulkRows(), s.batchSize(), indexed);
            int n = indexed ? s.lookups() : s.scanLookups();
            Random rnd = new Random(7);
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int i = 0; i < n; i++) {
                int key = rnd.nextInt(s.bulkRows());
                long t0 = System.nanoTime();
                int found = rows(session.execute("SELECT * FROM t WHERE id = " + key + ";"));
                lat.record(System.nanoTime() - t0);
                if (found != 1) throw new IllegalStateException("lookup of " + key + " found " + found);
            }
            return m.finish(indexed ? "point_lookup_index" : "point_lookup_scan",
                "table=" + s.bulkRows() + " lookups=" + n, n, lat);
        }
    }

    /** Same as point_lookup_index, but forcing the hash index (point_lookup_index uses the default index). */
    Result pointLookupHash(Scale s) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            session.execute("CREATE TABLE t (id INTEGER, val BIGINT, pad VARCHAR);");
            session.execute("CREATE INDEX t_id ON t USING HASH (id);");
            loadRows(session, s.bulkRows(), s.batchSize());
            Random rnd = new Random(7);
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int i = 0; i < s.lookups(); i++) {
                int key = rnd.nextInt(s.bulkRows());
                long t0 = System.nanoTime();
                int found = rows(session.execute("SELECT * FROM t WHERE id = " + key + ";"));
                lat.record(System.nanoTime() - t0);
                if (found != 1) throw new IllegalStateException("lookup of " + key + " found " + found);
            }
            return m.finish("point_lookup_hash", "table=" + s.bulkRows() + " lookups=" + s.lookups(), s.lookups(), lat);
        }
    }

    /** 100-key range: an ordered (B+Tree) index scan once T8 landed; a full scan plus filter before. */
    Result rangeQuery(Scale s) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            load(session, s.bulkRows(), s.batchSize(), true);
            Random rnd = new Random(11);
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int i = 0; i < s.ranges(); i++) {
                int lo = rnd.nextInt(Math.max(1, s.bulkRows() - 100));
                long t0 = System.nanoTime();
                int found = rows(session.execute("SELECT * FROM t WHERE id >= " + lo + " AND id < " + (lo + 100) + ";"));
                lat.record(System.nanoTime() - t0);
                if (found != 100) throw new IllegalStateException("range found " + found);
            }
            return m.finish("range_query", "table=" + s.bulkRows() + " width=100 queries=" + s.ranges(), s.ranges(), lat);
        }
    }

    /** Throughput unit is rows scanned per second; latency is per full SELECT *. */
    Result fullScan(Scale s) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            load(session, s.bulkRows(), s.batchSize(), false);
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int i = 0; i < s.fullScans(); i++) {
                long t0 = System.nanoTime();
                int found = rows(session.execute("SELECT * FROM t;"));
                lat.record(System.nanoTime() - t0);
                if (found != s.bulkRows()) throw new IllegalStateException("scan found " + found);
            }
            return m.finish("full_scan", "table=" + s.bulkRows() + " scans=" + s.fullScans() + " (ops = rows scanned)",
                (long) s.fullScans() * s.bulkRows(), lat);
        }
    }

    Result updateIndexed(Scale s) throws Exception {
        try (Database db = freshDb(); Session session = db.newSession()) {
            load(session, s.bulkRows(), s.batchSize(), true);
            Random rnd = new Random(13);
            LatencyRecorder lat = new LatencyRecorder();
            Measurement m = Measurement.start(db);
            for (int i = 0; i < s.updates(); i++) {
                long t0 = System.nanoTime();
                session.execute("UPDATE t SET val = " + i + " WHERE id = " + rnd.nextInt(s.bulkRows()) + ";");
                lat.record(System.nanoTime() - t0);
            }
            return m.finish("update_indexed", "table=" + s.bulkRows() + " updates=" + s.updates() + " autocommit",
                s.updates(), lat);
        }
    }

    /** T threads, autocommit inserts, either into their own tables (no lock conflicts) or one shared table. */
    Result concurrentInsert(Scale s, int threads, boolean sameTable) throws Exception {
        try (Database db = freshDb()) {
            try (Session setup = db.newSession()) {
                for (int t = 0; t < (sameTable ? 1 : threads); t++) {
                    setup.execute("CREATE TABLE t" + t + " (id INTEGER, val BIGINT, pad VARCHAR);");
                }
            }
            LatencyRecorder[] lat = new LatencyRecorder[threads];
            Measurement m = Measurement.start(db);
            runThreads(threads, t -> {
                lat[t] = new LatencyRecorder();
                String table = sameTable ? "t0" : "t" + t;
                try (Session session = db.newSession()) {
                    for (int i = 0; i < s.perThreadInserts(); i++) {
                        int id = t * 1_000_000 + i;
                        long t0 = System.nanoTime();
                        session.execute("INSERT INTO " + table + " VALUES (" + id + ", " + id + ", '" + PAD + "');");
                        lat[t].record(System.nanoTime() - t0);
                    }
                }
            });
            LatencyRecorder all = new LatencyRecorder();
            for (LatencyRecorder r : lat) all.merge(r);
            String name = (sameTable ? "concurrent_insert_same_table_t" : "concurrent_insert_own_table_t") + threads;
            return m.finish(name, "threads=" + threads + " inserts/thread=" + s.perThreadInserts(),
                (long) threads * s.perThreadInserts(), all);
        }
    }

    /** T threads on one indexed table: 80% point SELECTs, 20% autocommit INSERTs. Readers and writers contend on the table lock. */
    Result mixed(Scale s, int threads) throws Exception {
        try (Database db = freshDb()) {
            try (Session setup = db.newSession()) {
                load(setup, s.bulkRows(), s.batchSize(), true);
            }
            LatencyRecorder[] lat = new LatencyRecorder[threads];
            Measurement m = Measurement.start(db);
            runThreads(threads, t -> {
                lat[t] = new LatencyRecorder();
                Random rnd = new Random(100 + t);
                try (Session session = db.newSession()) {
                    for (int i = 0; i < s.mixedOpsPerThread(); i++) {
                        String sql = rnd.nextInt(10) < 8
                            ? "SELECT * FROM t WHERE id = " + rnd.nextInt(s.bulkRows()) + ";"
                            : insertSql(10_000_000 + t * 1_000_000 + i);
                        long t0 = System.nanoTime();
                        session.execute(sql);
                        lat[t].record(System.nanoTime() - t0);
                    }
                }
            });
            LatencyRecorder all = new LatencyRecorder();
            for (LatencyRecorder r : lat) all.merge(r);
            return m.finish("mixed_80read_20write_t" + threads,
                "threads=" + threads + " ops/thread=" + s.mixedOpsPerThread() + " table=" + s.bulkRows(),
                (long) threads * s.mixedOpsPerThread(), all);
        }
    }

    // ---- helpers ----

    private Database freshDb() throws IOException {
        Path file = Files.createTempFile(workDir, "db", ".db");
        Files.delete(file);
        // A buffer pool large enough to hold the medium dataset; buffer pool sizing is measured separately.
        return Database.open(file, Database.Options.defaults().withBufferPoolPages(1024).withLockTimeoutMillis(60_000));
    }

    private static String insertSql(int id) {
        return "INSERT INTO t VALUES (" + id + ", " + (id * 31L) + ", '" + PAD + "');";
    }

    private static void load(Session session, int rows, int batch, boolean index) throws IOException {
        session.execute("CREATE TABLE t (id INTEGER, val BIGINT, pad VARCHAR);");
        if (index) session.execute("CREATE INDEX t_id ON t (id);");
        loadRows(session, rows, batch);
    }

    private static void loadRows(Session session, int rows, int batch) throws IOException {
        for (int base = 0; base < rows; base += batch) {
            session.execute("BEGIN;");
            for (int i = base; i < Math.min(rows, base + batch); i++) session.execute(insertSql(i));
            session.execute("COMMIT;");
        }
    }

    private static int rows(ExecutionResult result) {
        return ((ExecutionResult.Rows) result).rows().size();
    }

    interface ThreadBody {
        void run(int threadIndex) throws Exception;
    }

    private static void runThreads(int threads, ThreadBody body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            int idx = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    body.run(idx);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "bench-" + t);
            thread.start();
        }
        start.countDown();
        done.await();
        if (failure.get() != null) throw new RuntimeException("benchmark thread failed", failure.get());
    }

    /** Captures engine metrics, JVM allocation and GC counters around a measured section. */
    private static final class Measurement {
        private final Database db;
        private final Metrics metricsBefore;
        private final long allocBefore;
        private final long gcCountBefore;
        private final long gcMillisBefore;
        private final long t0;

        private Measurement(Database db) {
            this.db = db;
            this.metricsBefore = db.metrics();
            this.allocBefore = allocatedBytes();
            this.gcCountBefore = gcCount();
            this.gcMillisBefore = gcMillis();
            this.t0 = System.nanoTime();
        }

        static Measurement start(Database db) {
            System.gc();
            return new Measurement(db);
        }

        Result finish(String name, String params, long ops, LatencyRecorder lat) {
            double seconds = (System.nanoTime() - t0) / 1e9;
            Metrics delta = db.metrics().minus(metricsBefore);
            long alloc = allocatedBytes() - allocBefore;
            long gcs = gcCount() - gcCountBefore;
            long gcMs = gcMillis() - gcMillisBefore;
            System.gc();
            long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            return new Result(name, params, ops, seconds, lat.summarize(), delta, alloc, gcs, gcMs, heap);
        }

        private static long allocatedBytes() {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t) {
                return t.getTotalThreadAllocatedBytes();
            }
            return -1;
        }

        private static long gcCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        }

        private static long gcMillis() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        }
    }

    // ---- reporting ----

    private static Result median(List<Result> runs) {
        List<Result> sorted = new ArrayList<>(runs);
        sorted.sort((a, b) -> Double.compare(a.opsPerSec(), b.opsPerSec()));
        return sorted.get(sorted.size() / 2);
    }

    static String report(String env, List<List<Result>> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("# StrataDB workload benchmark\n\n```\n").append(env).append("```\n\n");
        sb.append("Median of runs by throughput; spread = min..max throughput across runs. Latency in microseconds.\n\n");
        sb.append("| scenario | ops/s | spread | p50 µs | p95 µs | p99 µs | max µs | fsyncs/op | WAL B/op | hit ratio | lock wait ms | alloc KB/op | GCs |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (List<Result> runs : all) {
            Result r = median(runs);
            double min = runs.stream().mapToDouble(Result::opsPerSec).min().orElse(0);
            double max = runs.stream().mapToDouble(Result::opsPerSec).max().orElse(0);
            Metrics mm = r.metrics();
            sb.append(String.format(Locale.ROOT,
                "| %s | %,.0f | %,.0f..%,.0f | %,.0f | %,.0f | %,.0f | %,.0f | %.2f | %,.0f | %s | %,.0f | %,.1f | %d |%n",
                r.scenario(), r.opsPerSec(), min, max,
                r.latency().p50() / 1e3, r.latency().p95() / 1e3, r.latency().p99() / 1e3, r.latency().max() / 1e3,
                (double) mm.totalFsyncs() / r.ops(), (double) mm.walBytes() / r.ops(),
                Double.isNaN(mm.bufferHitRatio()) ? "-" : String.format(Locale.ROOT, "%.3f", mm.bufferHitRatio()),
                mm.lockWaitNanos() / 1e6, r.allocatedBytes() / 1024.0 / r.ops(), r.gcCount()));
        }
        sb.append("\nParameters:\n\n");
        for (List<Result> runs : all) {
            sb.append("- `").append(runs.get(0).scenario()).append("`: ").append(runs.get(0).params()).append('\n');
        }
        return sb.toString();
    }

    static String csv(List<List<Result>> all) {
        StringBuilder sb = new StringBuilder(
            "scenario,run,ops,seconds,ops_per_sec,p50_ns,p95_ns,p99_ns,max_ns,disk_reads,disk_writes,disk_fsyncs,"
            + "buffer_hits,buffer_misses,evictions,wal_commits,wal_bytes,wal_fsyncs,lock_waits,lock_wait_ns,alloc_bytes,gc_count,gc_ms,heap_after\n");
        for (List<Result> runs : all) {
            for (int i = 0; i < runs.size(); i++) {
                Result r = runs.get(i);
                Metrics m = r.metrics();
                sb.append(String.join(",", Arrays.asList(
                    r.scenario(), String.valueOf(i + 1), String.valueOf(r.ops()), String.format(Locale.ROOT, "%.4f", r.seconds()),
                    String.format(Locale.ROOT, "%.1f", r.opsPerSec()),
                    String.valueOf(r.latency().p50()), String.valueOf(r.latency().p95()), String.valueOf(r.latency().p99()), String.valueOf(r.latency().max()),
                    String.valueOf(m.diskPageReads()), String.valueOf(m.diskPageWrites()), String.valueOf(m.diskFsyncs()),
                    String.valueOf(m.bufferHits()), String.valueOf(m.bufferMisses()), String.valueOf(m.bufferEvictions()),
                    String.valueOf(m.walCommits()), String.valueOf(m.walBytes()), String.valueOf(m.walFsyncs()),
                    String.valueOf(m.lockWaits()), String.valueOf(m.lockWaitNanos()), String.valueOf(r.allocatedBytes()),
                    String.valueOf(r.gcCount()), String.valueOf(r.gcMillis()), String.valueOf(r.heapUsedAfter())))).append('\n');
            }
        }
        return sb.toString();
    }

    static String environment(Scale scale, int runs, String label) {
        Runtime rt = Runtime.getRuntime();
        String gcs = ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", "));
        return String.join("\n",
            "label:      " + label,
            "date:       " + ZonedDateTime.now(),
            "scale:      " + scale,
            "runs:       " + runs + " measured (+1 warm-up at 1/10 size)",
            "java:       " + System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"),
            "jvm args:   " + ManagementFactory.getRuntimeMXBean().getInputArguments(),
            "gc:         " + gcs,
            "os:         " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")",
            "cpus:       " + rt.availableProcessors(),
            "max heap:   " + rt.maxMemory() / (1024 * 1024) + " MB",
            "temp dir:   " + System.getProperty("java.io.tmpdir"),
            "") ;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of temp files
                }
            });
        }
    }
}
