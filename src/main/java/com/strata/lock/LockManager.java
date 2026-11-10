package com.strata.lock;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared (S) and exclusive (X) locks over named resources (table names and the catalog, as
 * {@code Session} uses it). Transactions acquire locks as they go and release all of them at
 * the end ({@link #releaseAll}): strict two-phase locking.
 *
 * <h2>Compatibility</h2>
 * Any number of owners may hold S together; X excludes everyone else. An owner can re-acquire a
 * lock it holds, and upgrade S to X once it is the only holder.
 *
 * <h2>Fair, FIFO queues with targeted wake-ups</h2>
 * Each resource has a queue of waiting requests, each with its own {@link Condition}. A new
 * request is granted immediately only if it is compatible with the current holders <em>and
 * nobody is already waiting</em>; otherwise it joins the back of the queue. On release, the queue
 * is granted from the front until the first request that still conflicts, and only those
 * requests are woken.
 *
 * <p>The first version used one monitor with {@code notifyAll}: every release woke every waiter
 * on every resource (a thundering herd), and whichever woke first won, regardless of how long
 * others had waited. Benchmarks showed what that does under contention: with 8 writers on one
 * table, p99 latency of 70–145 ms and maximums above a second, and a steady stream of readers
 * could starve a writer indefinitely. FIFO granting bounds how long any request can be
 * overtaken; stopping at the first conflicting request means readers queue behind a waiting
 * writer instead of starving it.
 *
 * <p>Upgrades (S held, X wanted) go to the <em>front</em> of the queue: the requester already
 * holds S, so anyone queued behind it would have to wait for it anyway.
 *
 * <h2>Deadlock detection</h2>
 * When a request must wait, the manager checks whether waiting would close a cycle in the
 * <b>waits-for graph</b>: a waiting owner depends on every holder it conflicts with and on every
 * request queued ahead of it (FIFO means it can't be granted before them). Every edge is
 * created when its waiter enqueues, so any cycle is completed by some enqueue; checking each
 * enqueue therefore finds every deadlock, the moment it forms. The requester that would close
 * the cycle gets {@link DeadlockException} immediately (a cheap, standard victim choice) instead
 * of every member of the cycle waiting out its timeout. This includes the classic upgrade
 * deadlock: two owners holding S that both ask for X.
 *
 * <p>Timeouts remain as a backstop for waits that are long but not deadlocked.
 *
 * <h2>Latching</h2>
 * One {@link ReentrantLock} guards all lock-table state; critical sections are short (no I/O),
 * and waiting happens on per-request conditions, which release it.
 */
public final class LockManager {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    public enum LockMode { SHARED, EXCLUSIVE }

    private static final class Request {
        final Object owner;
        final Object resource;
        final LockMode mode;
        final Condition granted;
        boolean isGranted;

        Request(Object owner, Object resource, LockMode mode, Condition granted) {
            this.owner = owner;
            this.resource = resource;
            this.mode = mode;
            this.granted = granted;
        }
    }

    private static final class LockState {
        final Map<Object, LockMode> holders = new LinkedHashMap<>();
        final ArrayDeque<Request> queue = new ArrayDeque<>();
    }

    private final ReentrantLock latch = new ReentrantLock();
    private final Map<Object, LockState> locks = new HashMap<>();
    private final Map<Object, Set<Object>> heldByOwner = new HashMap<>();
    /** The request each owner is currently blocked on (a session runs one statement at a time). */
    private final Map<Object, Request> waitingRequest = new HashMap<>();
    private final long defaultTimeoutMillis;

    // Instrumentation; guarded by latch.
    private long acquisitions;
    private long waits;
    private long waitNanos;
    private long timeouts;
    private long deadlocks;

    public LockManager() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    public LockManager(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public void acquireShared(Object owner, Object resource) {
        acquire(owner, resource, LockMode.SHARED, defaultTimeoutMillis);
    }

    public void acquireShared(Object owner, Object resource, long timeoutMillis) {
        acquire(owner, resource, LockMode.SHARED, timeoutMillis);
    }

    public void acquireExclusive(Object owner, Object resource) {
        acquire(owner, resource, LockMode.EXCLUSIVE, defaultTimeoutMillis);
    }

    public void acquireExclusive(Object owner, Object resource, long timeoutMillis) {
        acquire(owner, resource, LockMode.EXCLUSIVE, timeoutMillis);
    }

    private void acquire(Object owner, Object resource, LockMode mode, long timeoutMillis) {
        latch.lock();
        try {
            acquisitions++;
            LockState state = locks.computeIfAbsent(resource, r -> new LockState());
            LockMode held = state.holders.get(owner);
            if (held == LockMode.EXCLUSIVE || held == mode) {
                return; // already holds something at least as strong
            }
            boolean upgrade = held == LockMode.SHARED;
            if ((upgrade || state.queue.isEmpty()) && compatible(state, owner, mode)) {
                grant(state, owner, resource, mode);
                return;
            }

            Request request = new Request(owner, resource, mode, latch.newCondition());
            if (upgrade) {
                state.queue.addFirst(request);
            } else {
                state.queue.addLast(request);
            }
            waitingRequest.put(owner, request);
            if (closesCycle(owner)) {
                state.queue.remove(request);
                waitingRequest.remove(owner);
                deadlocks++;
                grantWaiters(state); // our departure may unblock requests that were behind us
                throw new DeadlockException("Deadlock: " + owner + " waiting for a " + mode + " lock on " + resource
                    + " would complete a cycle of transactions waiting for each other; this transaction was chosen to abort");
            }

            waits++;
            long start = System.nanoTime();
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            try {
                while (!request.isGranted) {
                    if (remaining <= 0) {
                        state.queue.remove(request);
                        waitingRequest.remove(owner);
                        timeouts++;
                        grantWaiters(state);
                        throw new LockTimeoutException(
                            "Timed out waiting for a " + mode + " lock on " + resource + " (held by another transaction)");
                    }
                    try {
                        remaining = request.granted.awaitNanos(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (!request.isGranted) {
                            state.queue.remove(request);
                            waitingRequest.remove(owner);
                            grantWaiters(state);
                            throw new LockTimeoutException("Interrupted while waiting for a " + mode + " lock on " + resource);
                        }
                    }
                }
            } finally {
                waitNanos += System.nanoTime() - start;
            }
        } finally {
            latch.unlock();
        }
    }

    /** Releases every lock {@code owner} holds, granting newly compatible waiters in FIFO order. */
    public void releaseAll(Object owner) {
        latch.lock();
        try {
            Set<Object> resources = heldByOwner.remove(owner);
            if (resources == null) {
                return;
            }
            for (Object resource : resources) {
                LockState state = locks.get(resource);
                state.holders.remove(owner);
                grantWaiters(state);
                if (state.holders.isEmpty() && state.queue.isEmpty()) {
                    // Safe to drop now (unlike v1, where a waiter could still hold a reference
                    // to a removed state): waiters always re-find state through their Request,
                    // and an idle resource has none.
                    locks.remove(resource);
                }
            }
        } finally {
            latch.unlock();
        }
    }

    // ---- internals (latch held) ----

    private static boolean compatible(LockState state, Object owner, LockMode mode) {
        for (Map.Entry<Object, LockMode> h : state.holders.entrySet()) {
            if (h.getKey().equals(owner)) continue;
            if (mode == LockMode.EXCLUSIVE || h.getValue() == LockMode.EXCLUSIVE) return false;
        }
        return true;
    }

    private void grant(LockState state, Object owner, Object resource, LockMode mode) {
        state.holders.put(owner, mode); // an upgrade replaces S with X
        heldByOwner.computeIfAbsent(owner, o -> new LinkedHashSet<>()).add(resource);
    }

    /** Grants queued requests from the front until the first one that still conflicts. */
    private void grantWaiters(LockState state) {
        while (!state.queue.isEmpty()) {
            Request next = state.queue.peekFirst();
            if (!compatible(state, next.owner, next.mode)) {
                return;
            }
            state.queue.pollFirst();
            grant(state, next.owner, next.resource, next.mode);
            waitingRequest.remove(next.owner);
            next.isGranted = true;
            next.granted.signal();
        }
    }

    /** True if {@code start}'s pending request makes it (transitively) wait for itself. */
    private boolean closesCycle(Object start) {
        ArrayDeque<Object> stack = new ArrayDeque<>();
        Set<Object> seen = new HashSet<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            for (Object next : waitsFor(current)) {
                if (next.equals(start)) return true;
                if (seen.add(next)) stack.push(next);
            }
        }
        return false;
    }

    /** Owners that {@code owner}'s pending request must wait for: conflicting holders, and everyone queued ahead. */
    private Set<Object> waitsFor(Object owner) {
        Request request = waitingRequest.get(owner);
        if (request == null) {
            return Set.of();
        }
        LockState state = locks.get(request.resource);
        Set<Object> out = new LinkedHashSet<>();
        for (Map.Entry<Object, LockMode> h : state.holders.entrySet()) {
            if (!h.getKey().equals(owner) && (request.mode == LockMode.EXCLUSIVE || h.getValue() == LockMode.EXCLUSIVE)) {
                out.add(h.getKey());
            }
        }
        for (Iterator<Request> it = state.queue.iterator(); it.hasNext(); ) {
            Request ahead = it.next();
            if (ahead == request) break;
            if (!ahead.owner.equals(owner)) out.add(ahead.owner);
        }
        return out;
    }

    // ---- instrumentation ----

    /** Lock requests made (granted or not). */
    public long acquisitionCount() {
        return withLatch(() -> acquisitions);
    }

    /** Requests that couldn't be granted immediately and had to wait. */
    public long waitCount() {
        return withLatch(() -> waits);
    }

    /** Total time requests spent waiting, granted or not. */
    public long waitNanos() {
        return withLatch(() -> waitNanos);
    }

    public long timeoutCount() {
        return withLatch(() -> timeouts);
    }

    /** Requests refused because they would have completed a deadlock cycle. */
    public long deadlockCount() {
        return withLatch(() -> deadlocks);
    }

    private long withLatch(java.util.function.LongSupplier read) {
        latch.lock();
        try {
            return read.getAsLong();
        } finally {
            latch.unlock();
        }
    }
}
