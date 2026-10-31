package dev.tejaswini.miniredis.dashboard;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandOrigin;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;
import dev.tejaswini.miniredis.protocol.RespWriter;
import dev.tejaswini.miniredis.stats.ServerStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The dashboard's JSON endpoints. Every method returns plain Maps/Lists
 * that {@link Json} turns into the response body.
 *
 * <p>Any read of the keyspace runs inside {@link CommandExecutor#callExclusive},
 * i.e. under the same lock as command execution, so the dashboard always
 * sees a consistent snapshot - never a list half-way through an RPUSH.
 *
 * <p>All output sizes are capped (number of keys listed, elements per
 * value, characters per string) so one huge value can't make the browser
 * or the server choke.
 */
public final class DashboardApi {

    /** Static facts about the running server, shown in the dashboard header. */
    public record ServerInfo(String version, String redisBindAddress, int redisPort, boolean aofEnabled) {
    }

    static final int MAX_KEYS_LISTED = 500;
    static final int MAX_ELEMENTS = 500;
    static final int MAX_KEY_CHARS = 1_024;
    static final int MAX_STRING_CHARS = 10_000;
    static final int MAX_RESP_PREVIEW_CHARS = 4_000;
    private static final int MAX_REPLY_ITEMS = 1_000;
    private static final Set<String> KEY_TYPES = Set.of("string", "list", "hash", "set");

    private final CommandExecutor executor;
    private final ServerStats stats;
    private final ServerInfo info;
    private final boolean authRequired;

    DashboardApi(CommandExecutor executor, ServerStats stats, ServerInfo info, boolean authRequired) {
        this.executor = executor;
        this.stats = stats;
        this.info = info;
        this.authRequired = authRequired;
    }

    Map<String, Object> session(boolean authenticated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authRequired", authRequired);
        result.put("authenticated", authenticated);
        if (authenticated) {
            // Only reveal server details after login.
            result.put("version", info.version());
            result.put("bind", info.redisBindAddress());
            result.put("port", info.redisPort());
            result.put("aofEnabled", info.aofEnabled());
            result.put("commands", executor.registeredCommands());
        }
        return result;
    }

    Map<String, Object> stats() {
        Map<String, Long> byType = executor.callExclusive(() -> {
            Database db = executor.database();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String type : List.of("string", "list", "hash", "set")) {
                counts.put(type, 0L);
            }
            for (String key : db.keys()) {
                counts.merge(db.type(key), 1L, Long::sum);
            }
            return counts;
        });
        long totalKeys = byType.values().stream().mapToLong(Long::longValue).sum();

        long[] history = stats.opsPerSecondHistory();
        List<Map<String, Object>> top = new ArrayList<>();
        stats.commandCounts().entrySet().stream().limit(8)
                .forEach(e -> top.add(Map.of("name", e.getKey(), "count", e.getValue())));

        Runtime rt = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uptimeMillis", stats.uptimeMillis());
        result.put("keys", totalKeys);
        result.put("keysByType", byType);
        result.put("connectedClients", stats.connectedClients());
        result.put("totalConnections", stats.totalConnections());
        result.put("rejectedConnections", stats.rejectedConnections());
        result.put("totalCommands", stats.totalCommands());
        result.put("totalErrors", stats.totalErrors());
        result.put("opsPerSecond", history[history.length - 1]);
        result.put("opsHistory", history);
        result.put("topCommands", top);
        result.put("heapUsedBytes", rt.totalMemory() - rt.freeMemory());
        result.put("heapMaxBytes", rt.maxMemory());
        return result;
    }

    Map<String, Object> keys(String pattern, String typeFilter) {
        String type = typeFilter.toLowerCase(Locale.ROOT);
        if (!type.equals("all") && !KEY_TYPES.contains(type)) {
            throw new DashboardServer.HttpError(400, "Unknown type filter");
        }
        if (pattern.isEmpty()) {
            pattern = "*";
        }
        String glob = pattern;
        return executor.callExclusive(() -> {
            Database db = executor.database();
            TreeSet<String> sorted = new TreeSet<>(db.keysMatching(glob));
            List<Map<String, Object>> rows = new ArrayList<>();
            int matched = 0;
            for (String key : sorted) {
                String keyType = db.type(key);
                if (!type.equals("all") && !type.equals(keyType)) {
                    continue;
                }
                matched++;
                if (rows.size() < MAX_KEYS_LISTED) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", clip(key, MAX_KEY_CHARS));
                    row.put("clipped", key.length() > MAX_KEY_CHARS); // giant key names can't be inspected
                    row.put("type", keyType);
                    row.put("ttlMillis", db.ttlMillis(key));
                    rows.add(row);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", matched);
            result.put("truncated", matched > rows.size());
            result.put("keys", rows);
            return result;
        });
    }

    Map<String, Object> key(String name) {
        return executor.callExclusive(() -> {
            Database db = executor.database();
            String type = db.type(name);
            if (type.equals("none")) {
                throw new DashboardServer.HttpError(404, "Key not found (it may have expired)");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", name);
            result.put("type", type);
            result.put("ttlMillis", db.ttlMillis(name));
            switch (type) {
                case "string" -> {
                    String value = db.getString(name);
                    result.put("size", value.length());
                    result.put("value", clip(value, MAX_STRING_CHARS));
                    result.put("truncated", value.length() > MAX_STRING_CHARS);
                }
                case "list" -> putCollection(result, db.getList(name));
                case "set" -> putCollection(result, db.getSet(name));
                case "hash" -> {
                    Map<String, String> hash = db.getHash(name);
                    List<Map<String, String>> fields = new ArrayList<>();
                    for (Map.Entry<String, String> e : hash.entrySet()) {
                        if (fields.size() >= MAX_ELEMENTS) break;
                        fields.add(Map.of("field", clip(e.getKey(), 2_000), "value", clip(e.getValue(), 2_000)));
                    }
                    result.put("size", hash.size());
                    result.put("value", fields);
                    result.put("truncated", hash.size() > MAX_ELEMENTS);
                }
                default -> throw new IllegalStateException("unexpected type " + type);
            }
            return result;
        });
    }

    private static void putCollection(Map<String, Object> result, Collection<String> items) {
        List<String> values = new ArrayList<>();
        for (String item : items) {
            if (values.size() >= MAX_ELEMENTS) break;
            values.add(clip(item, 2_000));
        }
        result.put("size", items.size());
        result.put("value", values);
        result.put("truncated", items.size() > MAX_ELEMENTS);
    }

    /**
     * Runs a console command. Returns the structured reply for pretty
     * printing <i>and</i> the raw RESP bytes of both the request and the
     * reply, so the UI can show exactly what travels over the wire.
     */
    Map<String, Object> command(String line) {
        List<String> args;
        try {
            args = CommandLineParser.parse(line.strip());
        } catch (IllegalArgumentException e) {
            return commandResult(List.of(), new Reply.Error("ERR " + e.getMessage()), 0);
        }
        if (args.isEmpty()) {
            throw new DashboardServer.HttpError(400, "Empty command");
        }
        String name = args.get(0).toUpperCase(Locale.ROOT);
        Reply reply;
        long start = System.nanoTime();
        if (name.equals("AUTH") || name.equals("QUIT")) {
            reply = new Reply.Error("ERR " + name + " is a connection command and isn't needed in the dashboard");
        } else {
            reply = executor.execute(args, CommandOrigin.DASHBOARD);
        }
        return commandResult(args, reply, (System.nanoTime() - start) / 1000);
    }

    private static Map<String, Object> commandResult(List<String> args, Reply reply, long micros) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", replyToJson(reply, 0));
        List<Reply> request = new ArrayList<>();
        args.forEach(a -> request.add(new Reply.BulkString(a)));
        result.put("requestResp", args.isEmpty() ? "" : clip(encode(new Reply.Array(request)), MAX_RESP_PREVIEW_CHARS));
        result.put("replyResp", clip(encode(reply), MAX_RESP_PREVIEW_CHARS));
        result.put("micros", micros);
        return result;
    }

    /** Reply -&gt; {type, value} so the UI can format it like redis-cli does. */
    private static Map<String, Object> replyToJson(Reply reply, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (reply) {
            case Reply.SimpleString s -> {
                out.put("type", "simple");
                out.put("value", s.value());
            }
            case Reply.Error e -> {
                out.put("type", "error");
                out.put("value", e.message());
            }
            case Reply.Integer i -> {
                out.put("type", "integer");
                out.put("value", i.value());
            }
            case Reply.BulkString b -> {
                out.put("type", b.value() == null ? "nil" : "bulk");
                out.put("value", b.value() == null ? null : clip(b.value(), MAX_STRING_CHARS));
            }
            case Reply.Array a -> {
                if (a.items() == null) {
                    out.put("type", "nil");
                    out.put("value", null);
                } else {
                    List<Object> items = new ArrayList<>();
                    for (Reply item : a.items()) {
                        if (items.size() >= MAX_REPLY_ITEMS || depth > 8) break;
                        items.add(replyToJson(item, depth + 1));
                    }
                    out.put("type", "array");
                    out.put("value", items);
                    out.put("truncated", a.items().size() > items.size());
                }
            }
        }
        return out;
    }

    private static String encode(Reply reply) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            RespWriter.write(buffer, reply);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // can't happen with an in-memory stream
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    List<Map<String, Object>> activity(long afterId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServerStats.Activity a : stats.activitySince(afterId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.id());
            row.put("time", a.timestampMillis());
            row.put("origin", a.origin());
            row.put("command", a.command());
            row.put("args", a.argsPreview());
            row.put("error", a.error());
            row.put("micros", a.micros());
            rows.add(row);
        }
        return rows;
    }

    /** Fills the keyspace with a small, realistic-looking sample dataset (fake data only). */
    Map<String, Object> loadDemoData() {
        int executed = 0;
        for (List<String> command : DemoData.COMMANDS) {
            executor.execute(command, CommandOrigin.DASHBOARD);
            executed++;
        }
        return Map.of("executed", executed);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
