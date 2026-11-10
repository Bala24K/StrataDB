package com.strata.lock;

import static com.strata.testutil.Assert.*;

import com.strata.engine.Database;
import com.strata.executor.ExecutionResult;
import com.strata.transaction.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** FIFO fairness, targeted wake-ups, and immediate deadlock detection. */
public class LockManagerV2Test {

    public void testDeadlockIsDetectedImmediatelyNotByTimeout() throws Exception {
        LockManager lm = new LockManager(30_000); // a timeout far longer than the test may take
        lm.acquireExclusive("t1", "A");
        lm.acquireExclusive("t2", "B");
        CountDownLatch t1Waiting = new CountDownLatch(1);
        AtomicReference<Throwable> t1Result = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                t1Waiting.countDown();
                lm.acquireExclusive("t1", "B"); // waits for t2
            } catch (Throwable e) {
                t1Result.set(e);
            }
        });
        t1.start();
        t1Waiting.await();
        waitUntilWaiting(lm, 1);

        long start = System.nanoTime();
        assertThrows(DeadlockException.class, () -> lm.acquireExclusive("t2", "A"), "t2 closes the cycle and must be the victim");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "detection must not wait for the 30 s timeout");
        assertEquals(1L, lm.deadlockCount());

        lm.releaseAll("t2"); // the victim aborts...
        t1.join(5_000);
        assertTrue(t1Result.get() == null, "...and the survivor gets its lock: " + t1Result.get());
        lm.releaseAll("t1");
    }

    public void testUpgradeDeadlockIsDetected() throws Exception {
        LockManager lm = new LockManager(30_000);
        lm.acquireShared("t1", "A");
        lm.acquireShared("t2", "A");
        Thread t1 = new Thread(() -> {
            try {
                lm.acquireExclusive("t1", "A"); // upgrade: waits for t2's S
            } catch (LockTimeoutException ignored) {
                // not expected; checked via deadlock count below
            }
        });
        t1.start();
        waitUntilWaiting(lm, 1);
        assertThrows(DeadlockException.class, () -> lm.acquireExclusive("t2", "A"));
        lm.releaseAll("t2");
        t1.join(5_000);
        assertFalse(t1.isAlive(), "t1's upgrade must be granted once t2 aborts");
        lm.releaseAll("t1");
    }

    /** Readers arriving after a waiting writer queue behind it instead of starving it. */
    public void testWaitingWriterIsNotStarvedByLaterReaders() throws Exception {
        LockManager lm = new LockManager(10_000);
        lm.acquireShared("reader0", "T");
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread writer = new Thread(() -> {
            lm.acquireExclusive("writer", "T");
            order.add("writer");
            lm.releaseAll("writer");
        });
        writer.start();
        waitUntilWaiting(lm, 1);
        Thread lateReader = new Thread(() -> {
            lm.acquireShared("reader1", "T"); // compatible with reader0, but a writer is waiting
            order.add("reader1");
            lm.releaseAll("reader1");
        });
        lateReader.start();
        waitUntilWaiting(lm, 2);
        assertEquals(List.of(), order, "the late reader must not barge past the waiting writer");

        lm.releaseAll("reader0");
        writer.join(5_000);
        lateReader.join(5_000);
        assertEquals(List.of("writer", "reader1"), order);
    }

    public void testCompatibleWaitersAreGrantedTogether() throws Exception {
        LockManager lm = new LockManager(10_000);
        lm.acquireExclusive("w", "T");
        CountDownLatch granted = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            String owner = "r" + i;
            new Thread(() -> {
                lm.acquireShared(owner, "T");
                granted.countDown();
            }).start();
        }
        waitUntilWaiting(lm, 3);
        lm.releaseAll("w");
        assertTrue(granted.await(5, TimeUnit.SECONDS), "one release must grant every compatible reader at the head of the queue");
    }

    /** At the session level the victim's whole transaction is rolled back, so the other proceeds. */
    public void testDeadlockVictimTransactionIsRolledBackAndTheOtherCommits() throws Exception {
        Path tmp = Files.createTempFile("stratadb-deadlock", ".db");
        Files.deleteIfExists(tmp);
        try (Database db = Database.open(tmp, Database.Options.defaults().withLockTimeoutMillis(30_000))) {
            Session setup = db.newSession();
            setup.execute("CREATE TABLE a (id INTEGER);");
            setup.execute("CREATE TABLE b (id INTEGER);");

            Session s1 = db.newSession();
            Session s2 = db.newSession();
            s1.execute("BEGIN;");
            s1.execute("INSERT INTO a VALUES (1);");
            s2.execute("BEGIN;");
            s2.execute("INSERT INTO b VALUES (2);");

            AtomicReference<Throwable> s1Failure = new AtomicReference<>();
            Thread t1 = new Thread(() -> {
                try {
                    s1.execute("INSERT INTO b VALUES (3);"); // waits for s2
                    s1.execute("COMMIT;");
                } catch (Throwable e) {
                    s1Failure.set(e);
                }
            });
            t1.start();
            waitUntilWaiting(db.lockManager(), 1);

            assertThrows(DeadlockException.class, () -> s2.execute("INSERT INTO a VALUES (4);"));
            assertFalse(s2.inTransaction(), "the victim's transaction must be rolled back entirely");
            t1.join(10_000);
            assertTrue(s1Failure.get() == null, "the survivor must complete: " + s1Failure.get());

            assertEquals(1, ((ExecutionResult.Rows) setup.execute("SELECT * FROM a;")).rows().size());
            assertEquals(1, ((ExecutionResult.Rows) setup.execute("SELECT * FROM b;")).rows().size(),
                "only s1's row (3): s2's insert of 2 was rolled back with its transaction");
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(Path.of(tmp + ".wal"));
        }
    }

    private static void waitUntilWaiting(LockManager lm, long waits) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lm.waitCount() < waits && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(20); // let the waiter actually park on its condition
    }
}
