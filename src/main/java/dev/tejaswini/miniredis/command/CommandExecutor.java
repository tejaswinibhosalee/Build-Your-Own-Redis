package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.persistence.AofWriter;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The command dispatch table: looks a command name up in a registry and
 * runs it.
 *
 * <p><b>Why the lock?</b> Real Redis is famously single-threaded - every
 * command runs start-to-finish before the next one begins, which is what
 * makes something like {@code INCR} (read the current value, add one,
 * write it back) safe without the client ever needing to worry about
 * another client's command interleaving in the middle. Our server instead
 * gives each client connection its own (virtual) thread for I/O, so we
 * reproduce that same guarantee explicitly: {@link #execute} takes a
 * single {@link ReentrantLock} before running a command and releases it
 * after, so only one command across all connected clients ever executes
 * at a time. It is a deliberate simplification - a real high-performance
 * engine would shard this lock or go fully single-threaded with an event
 * loop - but it is easy to reason about and easy to explain, which is the
 * point of this project.
 */
public final class CommandExecutor {

    private record CommandEntry(boolean isWrite, CommandHandler handler) {
    }

    private final CommandContext context;
    private final Map<String, CommandEntry> commands = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final List<CommandListener> listeners = new CopyOnWriteArrayList<>();

    public CommandExecutor(Database db, AofWriter aof) {
        this.context = new CommandContext(db, aof);
    }

    /**
     * Registers a command handler.
     *
     * @param name    command name, case-insensitive (e.g. "SET")
     * @param isWrite whether successful calls should be appended to the
     *                AOF log for durability - true for anything that
     *                mutates the keyspace, false for reads like GET
     */
    public void register(String name, boolean isWrite, CommandHandler handler) {
        commands.put(name.toUpperCase(Locale.ROOT), new CommandEntry(isWrite, handler));
    }

    /** Adds an observer that is told about every executed command (see {@link CommandListener}). */
    public void addListener(CommandListener listener) {
        listeners.add(listener);
    }

    /** Executes a command sent by a network client. */
    public Reply execute(List<String> args) {
        return execute(args, CommandOrigin.NETWORK);
    }

    /**
     * Executes a command and, if it is a successful write, appends it to the
     * AOF - unless {@code origin} is {@link CommandOrigin#AOF_REPLAY}: commands
     * replayed from the AOF at startup must not be re-appended, or every
     * restart would duplicate the log.
     */
    public Reply execute(List<String> args, CommandOrigin origin) {
        if (args.isEmpty()) {
            return new Reply.Error("ERR empty command");
        }
        String name = args.get(0).toUpperCase(Locale.ROOT);
        CommandEntry entry = commands.get(name);
        if (entry == null) {
            Reply unknown = new Reply.Error("ERR unknown command '" + truncate(args.get(0)) + "'");
            notifyListeners(null, args, unknown, 0, origin);
            return unknown;
        }

        Reply reply;
        long elapsed;
        lock.lock();
        try {
            long start = System.nanoTime();
            reply = runHandler(entry, args);
            elapsed = System.nanoTime() - start;
            boolean logToAof = origin != CommandOrigin.AOF_REPLAY;
            if (logToAof && entry.isWrite() && context.aof() != null && !(reply instanceof Reply.Error)) {
                context.aof().append(args);
            }
        } finally {
            lock.unlock();
        }
        notifyListeners(name, args, reply, elapsed, origin);
        return reply;
    }

    private Reply runHandler(CommandEntry entry, List<String> args) {
        try {
            return entry.handler().handle(context, args);
        } catch (CommandException e) {
            return new Reply.Error(e.getMessage());
        } catch (IndexOutOfBoundsException e) {
            // A handler read args.get(n) past the end: the client sent too few arguments.
            return new Reply.Error("ERR wrong number of arguments for '"
                    + args.get(0).toLowerCase(Locale.ROOT) + "' command");
        } catch (RuntimeException e) {
            // Don't echo arbitrary internal exception text back to clients -
            // it can leak implementation details. Log it server-side instead.
            System.err.println("Internal error executing " + args.get(0) + ": " + e);
            return new Reply.Error("ERR internal error");
        }
    }

    private void notifyListeners(String name, List<String> args, Reply reply, long nanos, CommandOrigin origin) {
        for (CommandListener listener : listeners) {
            try {
                listener.onCommand(name, args, reply, nanos, origin);
            } catch (RuntimeException e) {
                // A broken observer must never break command execution.
                System.err.println("Command listener failed: " + e);
            }
        }
    }

    /** Keeps attacker-controlled text in error replies short. */
    private static String truncate(String s) {
        return s.length() <= 64 ? s : s.substring(0, 64) + "...";
    }

    /** @return true if {@code name} (any case) is a registered command. */
    public boolean isRegistered(String name) {
        return commands.containsKey(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Runs an arbitrary task while holding the same lock as command
     * execution. Used by the background expiry sweep so it can never race
     * with a command that is reading or writing the keyspace.
     */
    public void runExclusive(Runnable task) {
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    /** Like {@link #runExclusive} but returns a value - used by the dashboard to take consistent snapshots. */
    public <T> T callExclusive(java.util.function.Supplier<T> task) {
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }

    /** All registered command names, sorted (used for the dashboard console's autocomplete). */
    public java.util.SortedSet<String> registeredCommands() {
        return new java.util.TreeSet<>(commands.keySet());
    }

    public Database database() {
        return context.db();
    }
}
