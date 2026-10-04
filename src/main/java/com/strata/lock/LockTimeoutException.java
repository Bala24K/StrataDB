package com.strata.lock;

/**
 * Thrown when a lock request can't be granted within its timeout. This is how
 * {@link LockManager} handles deadlocks: rather than building and analyzing a wait-for graph to
 * detect cycles proactively, a request that waits too long simply gives up. See
 * {@link LockManager}'s class docs for the trade-offs of that choice.
 */
public final class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(String message) {
        super(message);
    }
}
