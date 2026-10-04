package com.strata.lock;

import static com.strata.testutil.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LockManagerTest {

    // ---- compatibility matrix (no real threads needed — these are about state, not timing) ----

    public void testSharedLocksFromDifferentOwnersAreCompatible() {
        LockManager lm = new LockManager();
        lm.acquireShared("t1", "resourceA");
        lm.acquireShared("t2", "resourceA"); // must not throw
    }

    public void testExclusiveLockExcludesOtherOwnersExclusiveRequest() {
        LockManager lm = new LockManager(100);
        lm.acquireExclusive("t1", "resourceA");
        assertThrows(LockTimeoutException.class, () -> lm.acquireExclusive("t2", "resourceA"));
    }

    public void testExclusiveLockExcludesOtherOwnersSharedRequest() {
        LockManager lm = new LockManager(100);
        lm.acquireExclusive("t1", "resourceA");
        assertThrows(LockTimeoutException.class, () -> lm.acquireShared("t2", "resourceA"));
    }

    public void testSharedLockExcludesOtherOwnersExclusiveRequest() {
        LockManager lm = new LockManager(100);
        lm.acquireShared("t1", "resourceA");
        assertThrows(LockTimeoutException.class, () -> lm.acquireExclusive("t2", "resourceA"));
    }

    public void testSameOwnerCanReacquireAndUpgradeItsOwnLock() {
        LockManager lm = new LockManager();
        lm.acquireShared("t1", "resourceA");
        lm.acquireShared("t1", "resourceA");   // reentrant — must not block on itself
        lm.acquireExclusive("t1", "resourceA"); // upgrade S -> X for the same owner — must not block
    }

    public void testLocksOnDifferentResourcesDoNotInterfere() {
        LockManager lm = new LockManager();
        lm.acquireExclusive("t1", "resourceA");
        lm.acquireExclusive("t2", "resourceB"); // unrelated resource — must not block
    }

    public void testReleaseAllFreesEveryResourceHeldByThatOwner() {
        LockManager lm = new LockManager(100);
        lm.acquireExclusive("t1", "resourceA");
        lm.acquireExclusive("t1", "resourceB");

        lm.releaseAll("t1");

        lm.acquireExclusive("t2", "resourceA");
        lm.acquireExclusive("t2", "resourceB");
    }

    public void testReleaseAllOnUnknownOwnerIsHarmless() {
        LockManager lm = new LockManager();
        lm.releaseAll("nobody-ever-acquired-anything-as-this-owner");
    }

    // ---- real concurrency: latch-coordinated for deterministic interleaving, not sleep()-based ----

    public void testExclusiveLockActuallyBlocksAnotherThreadUntilReleased() throws Exception {
        LockManager lm = new LockManager(2_000);
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch mainMayRelease = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            lm.acquireExclusive("holder", "resourceA");
            holderAcquired.countDown();
            awaitQuietly(mainMayRelease);
            lm.releaseAll("holder");
        });
        holder.start();

        assertTrue(holderAcquired.await(1, TimeUnit.SECONDS), "the holder thread must acquire its lock promptly");

        // the holder is still holding the lock — a short-timeout attempt from elsewhere must fail
        assertThrows(LockTimeoutException.class, () -> lm.acquireExclusive("waiter", "resourceA", 100));

        mainMayRelease.countDown();
        holder.join(2_000);

        // now that the holder has released, acquiring must succeed promptly
        lm.acquireExclusive("waiter", "resourceA", 1_000);
    }

    public void testDeadlockIsBrokenByTimeoutRatherThanHangingForever() throws Exception {
        LockManager lm = new LockManager(300); // short, so a genuine deadlock resolves quickly
        CountDownLatch bothHaveFirstLock = new CountDownLatch(2);
        AtomicInteger timeouts = new AtomicInteger(0);

        Thread t1 = new Thread(() -> {
            lm.acquireExclusive("t1", "resourceA");
            bothHaveFirstLock.countDown();
            awaitQuietly(bothHaveFirstLock);
            try {
                lm.acquireExclusive("t1", "resourceB"); // held by t2 — this is the deadlock
            } catch (LockTimeoutException e) {
                timeouts.incrementAndGet();
            } finally {
                lm.releaseAll("t1");
            }
        });
        Thread t2 = new Thread(() -> {
            lm.acquireExclusive("t2", "resourceB");
            bothHaveFirstLock.countDown();
            awaitQuietly(bothHaveFirstLock);
            try {
                lm.acquireExclusive("t2", "resourceA"); // held by t1 — the other half of the cycle
            } catch (LockTimeoutException e) {
                timeouts.incrementAndGet();
            } finally {
                lm.releaseAll("t2");
            }
        });

        t1.start();
        t2.start();
        t1.join(3_000);
        t2.join(3_000);

        assertTrue(timeouts.get() >= 1, "a genuine deadlock must be broken by at least one timeout, not hang forever");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
