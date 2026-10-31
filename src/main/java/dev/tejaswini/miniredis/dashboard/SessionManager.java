package dev.tejaswini.miniredis.dashboard;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and validates dashboard login sessions.
 *
 * <p>After a successful password login the browser receives a random
 * 256-bit token and sends it back as {@code Authorization: Bearer <token>}
 * on every API call. Why this design:
 * <ul>
 *   <li><b>{@link SecureRandom}</b>, not {@code Random}: tokens must be
 *       unpredictable. {@code java.util.Random} is predictable from a few
 *       outputs and must never be used for secrets.</li>
 *   <li><b>A header, not a cookie</b>: browsers attach cookies to requests
 *       automatically - including ones triggered by other websites - which
 *       is exactly what CSRF attacks abuse. A header is only ever sent by
 *       our own JavaScript.</li>
 *   <li><b>Expiry</b>: sessions die after {@link #IDLE_TIMEOUT_MILLIS} of
 *       inactivity, and the number of live sessions is capped so the map
 *       can't grow without bound.</li>
 *   <li>The password itself is sent once, at login, and never stored by
 *       the browser.</li>
 * </ul>
 */
final class SessionManager {

    static final long IDLE_TIMEOUT_MILLIS = 8 * 60 * 60 * 1000L; // 8 hours
    static final int MAX_SESSIONS = 64;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    String create() {
        purgeExpired();
        if (lastSeen.size() >= MAX_SESSIONS) {
            evictOldest();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        lastSeen.put(token, System.currentTimeMillis());
        return token;
    }

    /** True if the token is known and not expired; refreshes its idle timer. */
    boolean validate(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long seen = lastSeen.get(token);
        long now = System.currentTimeMillis();
        if (seen == null || now - seen > IDLE_TIMEOUT_MILLIS) {
            lastSeen.remove(token);
            return false;
        }
        lastSeen.put(token, now);
        return true;
    }

    void invalidate(String token) {
        if (token != null) {
            lastSeen.remove(token);
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > IDLE_TIMEOUT_MILLIS);
    }

    private void evictOldest() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Iterator<Map.Entry<String, Long>> it = lastSeen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < oldestTime) {
                oldestTime = e.getValue();
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            lastSeen.remove(oldest);
        }
    }
}
