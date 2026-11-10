package com.strata.bench;

import java.util.Arrays;

/**
 * Records every individual operation latency (nanoseconds) and reports exact percentiles.
 * Workloads here are at most a few hundred thousand operations, so keeping every sample (and
 * sorting once) is simpler and more precise than a histogram. Not thread-safe: use one per
 * thread and {@link #merge}.
 */
final class LatencyRecorder {

    private long[] samples = new long[1024];
    private int count;

    void record(long nanos) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count++] = nanos;
    }

    void merge(LatencyRecorder other) {
        for (int i = 0; i < other.count; i++) {
            record(other.samples[i]);
        }
    }

    int count() {
        return count;
    }

    Summary summarize() {
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        return new Summary(count, pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), count == 0 ? 0 : sorted[count - 1]);
    }

    /** Nearest-rank percentile. */
    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    record Summary(int count, long p50, long p95, long p99, long max) {}
}
