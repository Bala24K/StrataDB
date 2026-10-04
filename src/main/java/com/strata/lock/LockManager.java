package com.strata.lock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared (S) and exclusive (X) locks over arbitrary named resources — table names, in how
 * StrataDB actually uses this (see {@code com.strata.transaction.Session}). This is what makes
 * it safe for more than one transaction to touch the database at once: a transaction acquires
 * the right lock before it reads or writes a table, and releases every lock it holds all at
 * once when it ends ({@link #releaseAll}) — the standard "strict two-phase locking" protocol
 * (acquire locks as needed, release them all only at the very end) that guarantees transactions
 * behave as if they ran one at a time, even when they're actually interleaved.
 *
 * <h2>Compatibility</h2>
 * <ul>
 *   <li>Two transactions can both hold a <b>shared</b> lock on the same resource at once
 *       (many concurrent readers is fine).</li>
 *   <li>A transaction holding an <b>exclusive</b> lock on a resource has it all to itself — no
 *       other transaction can hold anything, shared or exclusive, on that resource at the same
 *       time.</li>
 *   <li>A transaction can always re-acquire (or upgrade from shared to exclusive) a lock it
 *       already holds — that's never a conflict with itself.</li>
 * </ul>
 *
 * <h2>Built on plain Java monitors, on purpose</h2>
 * This is implemented with nothing but {@code synchronized}/{@code wait}/{@code notifyAll} —
 * the same low-level building blocks {@code java.util.concurrent} itself is built from, not a
 * borrowed concurrency library. A single intrinsic lock (this object's own monitor) guards every
 * resource's state; a thread that can't yet have the lock it wants calls {@code wait()}, which
 * atomically releases that monitor and blocks, until {@code notifyAll()} (called on every
 * release) wakes every waiter back up to recheck whether *its* request can now be granted.
 *
 * <p>This is simple and correct, but coarse: releasing <em>any</em> lock wakes up
 * <em>every</em> thread waiting on <em>any</em> resource, not just the ones who could actually
 * proceed. A production lock manager keeps a separate wait queue per resource to avoid that
 * thundering herd. Not worth the complexity at this project's scale — noted here rather than
 * built prematurely.
 *
 * <h2>Deadlocks: handled by timeout, not detection</h2>
 * Two transactions can deadlock — T1 holds X on A and wants X on B, while T2 holds X on B and
 * wants X on A — and without anything watching for that, both would {@code wait()} forever. The
 * real, more sophisticated fix is proactive <b>deadlock detection</b>: track who's waiting for
 * whom in a "wait-for" graph, and if it ever forms a cycle, abort one of the transactions in it
 * immediately. This class instead uses the simpler, still-real technique of a <b>timeout</b>:
 * every {@code acquire*} call gives up and throws {@link LockTimeoutException} if it hasn't been
 * granted within a bounded time. This is strictly less precise — a request can time out just
 * because the system is slow, not because it's actually deadlocked — but it's simple, it always
 * terminates, and it's a real strategy production systems use (sometimes alongside detection,
 * as a backstop). Proactive detection is a reasonable future improvement, not required for
 * correctness here.
 *
 * <h2>A real bug this design once had: never remove a live LockState from the map</h2>
 * {@code acquire()} captures a resource's {@code LockState} object once, at the top, and reuses
 * that same reference throughout its wait loop. An earlier version of {@link #releaseAll}
 * removed a resource's entry from {@code locks} entirely once nothing held it anymore — a
 * reasonable-looking memory optimization that was, in fact, a serious bug: a thread already
 * waiting on that object when it got removed would still be granted the lock against its now
 * orphaned copy, invisible to the map — while the very next unrelated {@code acquire()} call
 * for the same resource name would {@code computeIfAbsent} a brand-new, independent
 * {@code LockState}, letting two transactions hold what they each believed was an exclusive lock
 * on the same resource at once. It surfaced exactly where you'd hope a real bug would: a
 * multi-client network test with real contention (Phase 12's
 * {@code testManyConcurrentClientsInsertingDoNotLoseWrites}), where a handful of clients
 * succeeded fast and the rest hung until timeout — a symptom of later waiters stuck behind a
 * lock state nothing could ever correctly clear again. The fix is to simply never remove a
 * {@code LockState} once created; the map ends up with one permanent entry per resource ever
 * locked, which is a trivial memory cost at this project's scale.
 */
public final class LockManager {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    public enum LockMode { SHARED, EXCLUSIVE }

    private static final class LockState {
        final Set<Object> sharedHolders = new HashSet<>();
        Object exclusiveHolder;
    }

    private final Map<Object, LockState> locks = new HashMap<>();
    private final Map<Object, Set<Object>> heldByOwner = new HashMap<>();
    private final long defaultTimeoutMillis;

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

    private synchronized void acquire(Object owner, Object resource, LockMode mode, long timeoutMillis) {
        LockState state = locks.computeIfAbsent(resource, r -> new LockState());
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (!canGrant(state, owner, mode)) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new LockTimeoutException(
                    "Timed out waiting for a " + mode + " lock on " + resource + " (held by another transaction)");
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockTimeoutException("Interrupted while waiting for a " + mode + " lock on " + resource);
            }
        }

        if (mode == LockMode.SHARED) {
            state.sharedHolders.add(owner);
        } else {
            state.exclusiveHolder = owner;
            state.sharedHolders.remove(owner); // upgrading S -> X for the same owner: keep only the X entry
        }
        heldByOwner.computeIfAbsent(owner, o -> new HashSet<>()).add(resource);
    }

    private static boolean canGrant(LockState state, Object owner, LockMode mode) {
        boolean noConflictingExclusive = state.exclusiveHolder == null || state.exclusiveHolder.equals(owner);
        if (mode == LockMode.SHARED) {
            return noConflictingExclusive;
        }
        boolean noConflictingShared = state.sharedHolders.isEmpty()
            || (state.sharedHolders.size() == 1 && state.sharedHolders.contains(owner));
        return noConflictingExclusive && noConflictingShared;
    }

    /** Releases every lock {@code owner} currently holds, on every resource. Safe to call even if it holds none. */
    public synchronized void releaseAll(Object owner) {
        Set<Object> resources = heldByOwner.remove(owner);
        if (resources == null) {
            return;
        }
        for (Object resource : resources) {
            LockState state = locks.get(resource);
            if (state == null) {
                continue;
            }
            state.sharedHolders.remove(owner);
            if (owner.equals(state.exclusiveHolder)) {
                state.exclusiveHolder = null;
            }
            // Deliberately NOT removing `state` from `locks` even when it's now fully free — see
            // the class docs for why that used to be a real, serious bug: acquire() captures a
            // LockState reference once and keeps using it throughout its wait loop, so any
            // thread already waiting when this resource's entry got removed would end up
            // silently operating on an orphaned object, invisible to the next acquire() for the
            // same resource (which would just create a fresh one). A small number of permanent
            // entries — one per resource ever locked — is a trivial memory cost at this
            // project's scale, and it's what keeps every acquire() for the same resource
            // sharing the exact same LockState object, always.
        }
        notifyAll();
    }
}
