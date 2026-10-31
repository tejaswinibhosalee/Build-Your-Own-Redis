package dev.tejaswini.miniredis.stats;

import dev.tejaswini.miniredis.command.CommandListener;
import dev.tejaswini.miniredis.command.CommandOrigin;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Live server metrics, the same kind of numbers real Redis reports in
 * {@code INFO stats}: total commands, per-command counts, ops/sec over the
 * last minute, connected clients, and a short feed of recent commands.
 *
 * <p>It plugs into the executor as a {@link CommandListener} (the observer
 * pattern), so command execution code doesn't contain any metrics logic.
 *
 * <p><b>Concurrency:</b> counters use {@link LongAdder}/atomics, which are
 * cheap under contention. The per-second ring buffer and activity feed are
 * small and guarded by {@code synchronized} - they are updated once per
 * command and read once a second by the dashboard, so contention is tiny.
 */
public final class ServerStats implements CommandListener {

    /** Number of seconds of ops/sec history kept for the chart. */
    public static final int HISTORY_SECONDS = 60;
    /** Number of recent commands kept for the activity feed. */
    public static final int ACTIVITY_CAPACITY = 200;

    private static final int PREVIEW_ARG_CHARS = 40;
    private static final int PREVIEW_TOTAL_CHARS = 120;
    private static final String UNKNOWN_COMMAND = "(unknown)";

    /** One line of the activity feed. Arguments are truncated so huge values never bloat memory or the UI. */
    public record Activity(long id, long timestampMillis, CommandOrigin origin, String command,
                           String argsPreview, boolean error, long micros) {
    }

    private final long startedAtMillis = System.currentTimeMillis();
    private final LongAdder totalCommands = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final AtomicInteger connectedClients = new AtomicInteger();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final Map<String, LongAdder> perCommand = new ConcurrentHashMap<>();

    // Ring buffer of per-second command counts: slot = epochSecond % HISTORY_SECONDS.
    private final long[] secondCounts = new long[HISTORY_SECONDS];
    private final long[] secondStamps = new long[HISTORY_SECONDS];

    private final Deque<Activity> activity = new ArrayDeque<>(ACTIVITY_CAPACITY);
    private final AtomicLong activityIds = new AtomicLong();

    @Override
    public void onCommand(String commandName, List<String> args, Reply reply, long nanos, CommandOrigin origin) {
        if (origin == CommandOrigin.AOF_REPLAY) {
            return; // startup replay is not live traffic
        }
        boolean isError = reply instanceof Reply.Error;
        totalCommands.increment();
        if (isError) {
            totalErrors.increment();
        }
        // Only registered commands get their own counter. Counting arbitrary
        // client-supplied names would let anyone grow this map without bound.
        String key = commandName != null ? commandName : UNKNOWN_COMMAND;
        perCommand.computeIfAbsent(key, k -> new LongAdder()).increment();

        long nowMillis = System.currentTimeMillis();
        recordSecond(nowMillis / 1000);
        recordActivity(nowMillis, origin, key, args, isError, nanos / 1000);
    }

    private synchronized void recordSecond(long epochSecond) {
        int slot = (int) (epochSecond % HISTORY_SECONDS);
        if (secondStamps[slot] != epochSecond) {
            secondStamps[slot] = epochSecond; // slot held an old second: reset it
            secondCounts[slot] = 0;
        }
        secondCounts[slot]++;
    }

    private synchronized void recordActivity(long nowMillis, CommandOrigin origin, String command,
                                             List<String> args, boolean error, long micros) {
        if (activity.size() == ACTIVITY_CAPACITY) {
            activity.removeFirst();
        }
        activity.addLast(new Activity(activityIds.incrementAndGet(), nowMillis, origin, command,
                previewArgs(args), error, micros));
    }

    /** "foo bar baz" with every argument and the whole line truncated, control chars made visible. */
    static String previewArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.size() && sb.length() < PREVIEW_TOTAL_CHARS; i++) {
            String arg = args.get(i);
            if (arg.length() > PREVIEW_ARG_CHARS) {
                arg = arg.substring(0, PREVIEW_ARG_CHARS) + "…";
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(arg.replace("\r", "\\r").replace("\n", "\\n"));
        }
        if (sb.length() > PREVIEW_TOTAL_CHARS) {
            sb.setLength(PREVIEW_TOTAL_CHARS);
            sb.append('…');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Connection tracking (called by the TCP server)
    // ------------------------------------------------------------------

    public void clientConnected() {
        connectedClients.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    public void clientDisconnected() {
        connectedClients.decrementAndGet();
    }

    public void connectionRejected() {
        rejectedConnections.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Read side (used by the dashboard and INFO)
    // ------------------------------------------------------------------

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }

    public long totalCommands() {
        return totalCommands.sum();
    }

    public long totalErrors() {
        return totalErrors.sum();
    }

    public int connectedClients() {
        return connectedClients.get();
    }

    public long totalConnections() {
        return totalConnections.get();
    }

    public long rejectedConnections() {
        return rejectedConnections.get();
    }

    /** Command name -&gt; call count, highest first. */
    public Map<String, Long> commandCounts() {
        List<Map.Entry<String, LongAdder>> entries = new ArrayList<>(perCommand.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, LongAdder> e : entries) {
            result.put(e.getKey(), e.getValue().sum());
        }
        return result;
    }

    /**
     * Commands per second for each of the last {@link #HISTORY_SECONDS}
     * <i>completed</i> seconds, oldest first. The current, still-filling
     * second is excluded so the chart's right edge doesn't constantly dip.
     */
    public synchronized long[] opsPerSecondHistory() {
        long current = System.currentTimeMillis() / 1000;
        long[] result = new long[HISTORY_SECONDS];
        for (int i = 0; i < HISTORY_SECONDS; i++) {
            long second = current - HISTORY_SECONDS + i;
            int slot = (int) (second % HISTORY_SECONDS);
            result[i] = secondStamps[slot] == second ? secondCounts[slot] : 0;
        }
        return result;
    }

    /** Activity entries with {@code id > afterId}, oldest first. */
    public synchronized List<Activity> activitySince(long afterId) {
        List<Activity> result = new ArrayList<>();
        for (Activity a : activity) {
            if (a.id() > afterId) {
                result.add(a);
            }
        }
        return result;
    }
}
