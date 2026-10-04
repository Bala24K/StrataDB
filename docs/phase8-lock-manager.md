# Phase 8 — Lock Manager

Goal for this phase: shared and exclusive locks, so that — as the project plan put it — "two
users can't corrupt data." This is the first phase that requires genuine multi-threading:
everything before this assumed exactly one thread touching the database at a time.

## 1. What could actually go wrong without this

Phase 7 gave transactions real atomicity, durability, and isolation *for the single-active-
transaction case*. But nothing stopped two transactions from running at the same time and
interleaving in unsafe ways — for instance, both reading a row, both computing a new value based
on what they read, and both writing back, with one update silently clobbering the other (the
classic "lost update"). A lock manager is what prevents that: before touching a piece of data, a
transaction must hold the right lock on it, and holds that lock until it's done.

## 2. Shared vs. exclusive, and why that split exists

Two kinds of lock cover the two things a transaction can want to do:

- **Shared (S):** "I'm reading this; nobody else may write it while I do." Multiple
  transactions can all hold a shared lock on the same resource at once — many concurrent readers
  is completely safe, since none of them are changing anything.
- **Exclusive (X):** "I'm writing this; nobody else may read *or* write it while I do." An
  exclusive lock holder has the resource entirely to itself.

The compatibility rule this implies: S+S is fine, but X conflicts with everything (including
another X) held by a *different* transaction. A transaction can always re-acquire — or upgrade
from S to X — a lock it already holds itself; that's never a conflict with itself.

## 3. Strict two-phase locking: acquire as needed, release all at once

`LockManager` on its own just answers "can this lock be granted right now?" — the actual
*protocol* that makes a whole transaction's behavior safe is applied by `Session`: acquire the
right lock on a table right before running a statement against it, and hold every lock a
transaction has acquired until that transaction ends (`COMMIT` or `ROLLBACK`), releasing them
all at once via `releaseAll`. This is called **strict two-phase locking** (2PL) — "two-phase"
because a transaction only ever *grows* its set of held locks and then, at the very end, drops
all of them in one shot; it never releases one lock early and then acquires another. This
specific protocol is what guarantees transactions behave *as if* they ran one after another,
even though they're actually interleaved — the textbook-standard way locking achieves
serializability.

## 4. Built on plain Java monitors, on purpose

`LockManager` doesn't reach for `java.util.concurrent`'s ready-made lock classes — it's built
from `synchronized`/`wait`/`notifyAll`, the same primitives those classes are themselves built
from. A single intrinsic lock guards every resource's state; a thread that can't get the lock it
wants calls `wait()` (which atomically releases that monitor and blocks), and `notifyAll()` —
called on every release — wakes every waiter back up to recheck whether *its* particular request
can now be granted. This is simple and correct, but coarse: releasing any lock wakes up every
waiting thread, not just the ones who could actually proceed. A production system keeps a
separate wait queue per resource to avoid that. Not worth the added complexity here — noted
honestly rather than built prematurely.

## 5. Deadlocks: timeout, not detection

Two transactions can deadlock: T1 holds an exclusive lock on table A and wants one on table B,
while T2 holds an exclusive lock on B and wants one on A. Neither can ever proceed, and without
anything watching for this, both would `wait()` forever.

The more sophisticated real answer is **deadlock detection**: maintain a "wait-for" graph (who's
blocked waiting on whom) and, the moment it forms a cycle, abort one of the transactions in it
immediately. `LockManager` instead uses the simpler technique of a **timeout**: every
`acquire*` call gives up and throws `LockTimeoutException` if it hasn't been granted within a
bounded time. This is less precise — a slow-but-not-actually-deadlocked request can time out too
— but it's simple, it always terminates, and it's a real strategy production systems genuinely
use (sometimes layered with detection as a backstop, not always instead of it).
`LockManagerTest.testDeadlockIsBrokenByTimeoutRatherThanHangingForever` builds an actual
two-thread deadlock (coordinated with a `CountDownLatch` so the interleaving is deterministic,
not a timing guess) and confirms it resolves via timeout rather than hanging.

## 6. Writing deterministic tests for genuinely concurrent behavior

This phase's tests are the first ones in the project that need real threads, which is exactly
the kind of test that's easy to make flaky by accident (`Thread.sleep()`-based "probably enough
time has passed" tests are a classic source of tests that pass locally and fail under load). Every
multi-threaded test here instead uses a `CountDownLatch` to force a specific, guaranteed
interleaving — e.g. "don't let the main thread attempt its conflicting lock request until the
background thread has *definitely* acquired its own first" — so the test's correctness doesn't
depend on how fast either thread happens to run, only on the actual locking behavior being
correct. The suite was run repeatedly back-to-back while building this phase specifically to
check for exactly this kind of flakiness; it hasn't shown any.

## 7. Wiring it into `Session`

Adding real locking to `Session.execute()` needed remarkably little new machinery — mapping each
statement kind to the table it touches and the lock mode it needs:

```
CREATE TABLE / INSERT / UPDATE / DELETE  →  EXCLUSIVE lock on that statement's table
SELECT                                    →  SHARED lock on that statement's table
```

The lock is acquired under the **transaction's own identity** as the "owner" passed to
`LockManager` — for an explicit transaction, that's the shared `Transaction` object every
statement since `BEGIN` runs against, so the lock is naturally held across all of them until
`COMMIT`/`ROLLBACK`. For an autocommit statement, it's that one statement's own private
`Transaction`, released in a `finally` block right after — whether the statement succeeded or
threw. Multiple `Session`s sharing one `LockManager` instance (via the new
`Session.open(diskManager, lockManager)` overload) is what actually models "two users": each
`Session` stands in for an independent client, and `SessionConcurrencyTest` exercises exactly
that — two sessions reading concurrently without blocking, and one session's writer correctly
blocking another session's conflicting reader or writer until the first commits.

## 8. What Phase 8 deliberately does not do

- **Table-granularity locking only.** A write to any single row locks the *entire* table, not
  just that row — real systems typically lock at a much finer grain (row-level or finer) so
  unrelated writes to the same table can proceed concurrently. A reasonable future refinement,
  not a correctness requirement here.
- **No deadlock detection**, as covered in section 5 — timeout-based handling only.
- **No true multi-process concurrency.** "Two users" here means two `Session` objects (and, in
  the real blocking tests, two threads) sharing one `LockManager` within a single process on one
  open `DiskManager`. Actual multiple independent client connections need the network layer —
  Phase 12.
- **No lock upgrading queue fairness.** If several transactions are all waiting for the same
  resource, there's no guarantee about the order they're granted it in beyond whatever the JVM's
  thread scheduling happens to do — no starvation prevention.

## 9. What to look at in the code

- `src/main/java/com/strata/lock/LockManager.java` — the shared/exclusive lock table itself.
- `src/main/java/com/strata/lock/LockTimeoutException.java` — thrown on a timed-out request.
- `src/main/java/com/strata/transaction/Session.java` — extended this phase with the
  lock-request-per-statement-kind mapping and the shared-`LockManager` constructor overloads.
- `src/test/java/com/strata/lock/LockManagerTest.java` — 10 tests covering the full
  compatibility matrix, reentrant/upgrade behavior, and two genuinely multi-threaded tests
  (blocking-until-release, and the deadlock-via-timeout scenario).
- `src/test/java/com/strata/transaction/SessionConcurrencyTest.java` — 4 tests demonstrating
  the actual "two users" scenario at the SQL level: concurrent readers, a blocked writer, a
  blocked reader, and unrelated tables not interfering with each other.
