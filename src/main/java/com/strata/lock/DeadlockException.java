package com.strata.lock;

/**
 * Thrown to the transaction whose lock request would have closed a cycle in the waits-for graph
 * (T1 waits for T2 ... waits for T1). Nothing in a cycle can ever proceed, so one member must
 * give up; {@link LockManager} picks the requester that closed the cycle, at the moment it
 * closes it, instead of letting every member wait out its timeout.
 *
 * <p>A subclass of {@link LockTimeoutException} so existing handlers still apply. Unlike a
 * timeout, the right response is to abort the whole transaction (releasing its locks), which is
 * what {@code Session} does: merely failing the one statement would leave the victim holding the
 * locks the other transaction is waiting for.
 */
public final class DeadlockException extends LockTimeoutException {

    public DeadlockException(String message) {
        super(message);
    }
}
