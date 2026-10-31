package dev.tejaswini.miniredis.security;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Slows down password guessing.
 *
 * <p>Every failed login makes the <i>caller</i> wait before it gets an
 * answer. The wait grows with the number of recent failures (shared across
 * all connections, so opening many sockets doesn't help an attacker) and is
 * capped so a legitimate user who mistypes isn't locked out forever. A
 * successful login resets the counter.
 *
 * <p>With a 2s cap, an attacker gets at most ~1 guess per 2 seconds per
 * connection - harmless against a decent password, while a human typo only
 * costs a fraction of a second. Waiting is cheap for us because connections
 * run on virtual threads, so a sleeping attacker doesn't tie up an OS thread.
 */
public final class LoginThrottle {

    private static final long BASE_DELAY_MILLIS = 250;
    private static final long MAX_DELAY_MILLIS = 2_000;
    private static final long FORGET_AFTER_MILLIS = 60_000;

    private final AtomicLong recentFailures = new AtomicLong();
    private volatile long lastFailureAt;

    public void onSuccess() {
        recentFailures.set(0);
    }

    /** Records a failure and blocks the calling thread for the penalty delay. */
    public void onFailure() {
        long now = System.currentTimeMillis();
        if (now - lastFailureAt > FORGET_AFTER_MILLIS) {
            recentFailures.set(0);
        }
        lastFailureAt = now;
        long failures = recentFailures.incrementAndGet();
        long delay = Math.min(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS * failures);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
