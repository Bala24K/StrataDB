package com.strata.lock;

/**
 * Thrown when a lock request is not granted: because it waited past its timeout, or (as the
 * subclass {@link DeadlockException}) because waiting would have completed a deadlock cycle.
 */
public class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(String message) {
        super(message);
    }
}
