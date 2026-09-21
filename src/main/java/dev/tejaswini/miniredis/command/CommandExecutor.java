package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.persistence.AofWriter;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Executes a command and appends it to the AOF if it's a successful write. */
    public Reply execute(List<String> args) {
        return execute(args, true);
    }

    /**
     * @param logToAof pass {@code false} when replaying commands that were
     *                 already read back out of the AOF file on startup -
     *                 otherwise every replayed write would immediately be
     *                 re-appended, duplicating the log forever.
     */
    public Reply execute(List<String> args, boolean logToAof) {
        if (args.isEmpty()) {
            return new Reply.Error("ERR empty command");
        }
        String name = args.get(0).toUpperCase(Locale.ROOT);
        CommandEntry entry = commands.get(name);
        if (entry == null) {
            return new Reply.Error("ERR unknown command '" + args.get(0) + "'");
        }

        lock.lock();
        try {
            Reply reply = entry.handler().handle(context, args);
            if (logToAof && entry.isWrite() && context.aof() != null && !(reply instanceof Reply.Error)) {
                context.aof().append(args);
            }
            return reply;
        } catch (CommandException e) {
            return new Reply.Error(e.getMessage());
        } catch (Exception e) {
            return new Reply.Error("ERR " + e.getMessage());
        } finally {
            lock.unlock();
        }
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

    public Database database() {
        return context.db();
    }
}
