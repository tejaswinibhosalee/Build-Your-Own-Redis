package dev.tejaswini.miniredis.dashboard;

import java.util.List;

/**
 * A small sample dataset for demos, covering every data type plus a few
 * keys with short TTLs so you can watch expiry happen live in the UI.
 *
 * <p>All values are fake (the {@code example.com} domain is reserved for
 * documentation by RFC 2606).
 */
final class DemoData {

    private DemoData() {
    }

    static final List<List<String>> COMMANDS = List.of(
            // Strings
            List.of("SET", "greeting", "Hello from mini-redis!"),
            List.of("SET", "counter:page_views", "1336"),
            List.of("INCR", "counter:page_views"),
            List.of("SET", "config:feature_flags", "{\"darkMode\":true,\"betaSearch\":false}"),

            // Hashes - like a row in a table
            List.of("HSET", "user:1001", "name", "Ada Lovelace", "email", "ada@example.com",
                    "plan", "pro", "country", "UK"),
            List.of("HSET", "user:1002", "name", "Alan Turing", "email", "alan@example.com",
                    "plan", "free", "country", "UK"),
            List.of("HSET", "user:1003", "name", "Grace Hopper", "email", "grace@example.com",
                    "plan", "enterprise", "country", "US"),

            // Lists - queues and recent-activity feeds
            List.of("RPUSH", "queue:emails", "welcome:1001", "receipt:1002", "password-reset:1003"),
            List.of("LPUSH", "recent:logins", "user:1002", "user:1003", "user:1001"),

            // Sets - unique tags / membership
            List.of("SADD", "tags:java", "virtual-threads", "records", "sealed-interfaces", "pattern-matching"),
            List.of("SADD", "tags:redis", "resp", "aof", "ttl", "in-memory"),
            List.of("SADD", "online:users", "user:1001", "user:1003"),

            // Keys with a TTL - watch them count down and disappear
            List.of("SET", "session:9f2c41", "user=1001;role=admin", "EX", "300"),
            List.of("SET", "cache:homepage", "<html>...rendered page...</html>", "EX", "45"),
            List.of("SET", "otp:1002", "482913", "EX", "20"),
            List.of("SET", "rate:ip:203.0.113.7", "17", "EX", "60"));
}
