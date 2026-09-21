package dev.tejaswini.miniredis.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The actual in-memory keyspace: a single {@code Map<String, Object>} plus
 * a side map of per-key expiry timestamps.
 *
 * <p>Real Redis keeps every value type - strings, lists, hashes, sets,
 * sorted sets, streams - in <b>one</b> keyspace rather than a separate
 * table per type. A key means whatever the object stored under it is; a
 * command that expects a list on a key that holds a string fails with
 * {@code WRONGTYPE} rather than silently doing the wrong thing. We copy
 * that model directly: {@link #store} holds {@code String} for Redis
 * strings, {@code Deque<String>} for lists, {@code Map<String,String>} for
 * hashes and {@code LinkedHashSet<String>} for sets, and every accessor
 * below type-checks with {@code instanceof} before handing a value back.
 *
 * <p><b>Thread safety.</b> The maps are {@link ConcurrentHashMap}s so a
 * stray read is never corrupted, but that alone does not make compound
 * operations like INCR (read-then-write) atomic. Atomicity is provided one
 * layer up: {@code CommandExecutor} holds a single lock around the
 * execution of every command, deliberately mirroring the fact that real
 * Redis executes commands on one thread. Database itself stays simple and
 * lock-free.
 */
public final class Database {

    private final Map<String, Object> store = new ConcurrentHashMap<>();
    private final Map<String, Long> expiresAt = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------
    // Expiry
    // ---------------------------------------------------------------

    /**
     * Lazy expiry: called by every read/write before it looks at a key.
     * If the key has a TTL that has passed, it is removed right now and
     * treated as if it never existed - this is what Redis calls
     * "expire on access".
     */
    private boolean expireIfNeeded(String key) {
        Long expiry = expiresAt.get(key);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            store.remove(key);
            expiresAt.remove(key);
            return true;
        }
        return false;
    }

    public void setExpireAt(String key, long epochMillis) {
        expiresAt.put(key, epochMillis);
    }

    /** Removes any TTL on {@code key}, making it persist forever again. */
    public boolean persist(String key) {
        return expiresAt.remove(key) != null;
    }

    /** Milliseconds until expiry, or -1 if the key has no TTL, or -2 if it doesn't exist. */
    public long ttlMillis(String key) {
        if (expireIfNeeded(key) || !store.containsKey(key)) {
            return -2;
        }
        Long expiry = expiresAt.get(key);
        if (expiry == null) {
            return -1;
        }
        return Math.max(0, expiry - System.currentTimeMillis());
    }

    /**
     * Active expiry cycle: proactively sweeps keys that have a TTL and
     * removes the ones that have passed, instead of waiting for a client
     * to touch them. Real Redis samples a random subset of keys-with-TTL
     * many times a second; we do the same idea at a much smaller scale,
     * scanning the whole (small, educational-project-sized) expiry map.
     * Without this, a key nobody ever reads again would leak memory
     * forever despite having "expired".
     */
    public void activeExpireCycle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : expiresAt.entrySet()) {
            if (now >= entry.getValue()) {
                String key = entry.getKey();
                store.remove(key);
                expiresAt.remove(key);
            }
        }
    }

    // ---------------------------------------------------------------
    // Generic key operations
    // ---------------------------------------------------------------

    public boolean exists(String key) {
        expireIfNeeded(key);
        return store.containsKey(key);
    }

    public boolean delete(String key) {
        expireIfNeeded(key);
        expiresAt.remove(key);
        return store.remove(key) != null;
    }

    public void flushAll() {
        store.clear();
        expiresAt.clear();
    }

    public Set<String> keys() {
        activeExpireCycle();
        return Set.copyOf(store.keySet());
    }

    /** Matches Redis's KEYS glob syntax: {@code *} and {@code ?} only. */
    public Set<String> keysMatching(String globPattern) {
        Pattern regex = globToRegex(globPattern);
        Set<String> result = new LinkedHashSet<>();
        for (String key : keys()) {
            if (regex.matcher(key).matches()) {
                result.add(key);
            }
        }
        return result;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\' -> regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return Pattern.compile(regex.toString());
    }

    /** "string" / "list" / "hash" / "set" / "none" (key absent), like the real TYPE command. */
    public String type(String key) {
        expireIfNeeded(key);
        Object value = store.get(key);
        if (value == null) return "none";
        if (value instanceof String) return "string";
        if (value instanceof Deque) return "list";
        if (value instanceof Map) return "hash";
        if (value instanceof Set) return "set";
        return "unknown";
    }

    // ---------------------------------------------------------------
    // Strings
    // ---------------------------------------------------------------

    public void setString(String key, String value) {
        expiresAt.remove(key); // a plain SET clears any previous TTL, like real Redis
        store.put(key, value);
    }

    /** Returns the string at {@code key}, or null if absent. Throws if it's a different type. */
    public String getString(String key) {
        expireIfNeeded(key);
        Object value = store.get(key);
        if (value == null) return null;
        if (!(value instanceof String s)) throw new WrongTypeException();
        return s;
    }

    // ---------------------------------------------------------------
    // Lists (backed by ArrayDeque so push/pop at both ends are O(1))
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Deque<String> getList(String key) {
        expireIfNeeded(key);
        Object value = store.get(key);
        if (value == null) return null;
        if (!(value instanceof Deque)) throw new WrongTypeException();
        return (Deque<String>) value;
    }

    @SuppressWarnings("unchecked")
    public Deque<String> getOrCreateList(String key) {
        expireIfNeeded(key);
        Object existing = store.get(key);
        if (existing != null && !(existing instanceof Deque)) throw new WrongTypeException();
        return (Deque<String>) store.computeIfAbsent(key, k -> new ArrayDeque<String>());
    }

    // ---------------------------------------------------------------
    // Hashes
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, String> getHash(String key) {
        expireIfNeeded(key);
        Object value = store.get(key);
        if (value == null) return null;
        if (!(value instanceof Map)) throw new WrongTypeException();
        return (Map<String, String>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getOrCreateHash(String key) {
        expireIfNeeded(key);
        Object existing = store.get(key);
        if (existing != null && !(existing instanceof Map)) throw new WrongTypeException();
        return (Map<String, String>) store.computeIfAbsent(key, k -> new java.util.LinkedHashMap<String, String>());
    }

    // ---------------------------------------------------------------
    // Sets
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Set<String> getSet(String key) {
        expireIfNeeded(key);
        Object value = store.get(key);
        if (value == null) return null;
        if (!(value instanceof Set)) throw new WrongTypeException();
        return (Set<String>) value;
    }

    @SuppressWarnings("unchecked")
    public Set<String> getOrCreateSet(String key) {
        expireIfNeeded(key);
        Object existing = store.get(key);
        if (existing != null && !(existing instanceof Set)) throw new WrongTypeException();
        return (Set<String>) store.computeIfAbsent(key, k -> new LinkedHashSet<String>());
    }

    /** Removes a container key entirely once it becomes empty, matching Redis's behavior. */
    public void deleteIfEmpty(String key) {
        Object value = store.get(key);
        boolean empty = switch (value) {
            case Deque<?> d -> d.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            case Set<?> s -> s.isEmpty();
            case null, default -> false;
        };
        if (empty) {
            delete(key);
        }
    }
}
