package com.strata.metrics;

/**
 * A point-in-time snapshot of the engine's internal counters. Counters only ever increase, so
 * the cost of a workload is {@code after.minus(before)}.
 *
 * <p>These exist so benchmark results can be <em>explained</em>, not just reported: a throughput
 * number alone can't tell you whether a change saved fsyncs, cache misses or lock waits.
 */
public record Metrics(
    long diskPageReads,
    long diskPageWrites,
    long diskFsyncs,
    long diskPagesAllocated,
    long bufferHits,
    long bufferMisses,
    long bufferEvictions,
    long bufferDirtyWritebacks,
    long walCommits,
    long walBytes,
    long walFsyncs,
    long lockAcquisitions,
    long lockWaits,
    long lockWaitNanos,
    long lockTimeouts,
    long walCheckpoints,
    long lockDeadlocks
) {

    public static final Metrics ZERO = new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public Metrics minus(Metrics o) {
        return new Metrics(
            diskPageReads - o.diskPageReads,
            diskPageWrites - o.diskPageWrites,
            diskFsyncs - o.diskFsyncs,
            diskPagesAllocated - o.diskPagesAllocated,
            bufferHits - o.bufferHits,
            bufferMisses - o.bufferMisses,
            bufferEvictions - o.bufferEvictions,
            bufferDirtyWritebacks - o.bufferDirtyWritebacks,
            walCommits - o.walCommits,
            walBytes - o.walBytes,
            walFsyncs - o.walFsyncs,
            lockAcquisitions - o.lockAcquisitions,
            lockWaits - o.lockWaits,
            lockWaitNanos - o.lockWaitNanos,
            lockTimeouts - o.lockTimeouts,
            walCheckpoints - o.walCheckpoints,
            lockDeadlocks - o.lockDeadlocks);
    }

    /** Buffer pool hit ratio in [0, 1], or NaN if there were no lookups. */
    public double bufferHitRatio() {
        long lookups = bufferHits + bufferMisses;
        return lookups == 0 ? Double.NaN : (double) bufferHits / lookups;
    }

    /** Total fsync calls, data file plus log. */
    public long totalFsyncs() {
        return diskFsyncs + walFsyncs;
    }
}
