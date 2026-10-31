package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;

/**
 * Observer hook: notified after every command {@link CommandExecutor} runs.
 *
 * <p>This keeps the executor ignorant of who is watching it. Metrics, the
 * dashboard activity feed, or (in the future) a slow-log or replication
 * stream can all subscribe without the executor's code changing.
 */
@FunctionalInterface
public interface CommandListener {

    /**
     * @param commandName upper-cased command name if it is a registered
     *                    command, otherwise {@code null} (unknown command)
     * @param args        the full argument list, including the name at index 0
     * @param reply       what the command returned
     * @param nanos       how long execution took, excluding lock wait time
     * @param origin      who sent it
     */
    void onCommand(String commandName, List<String> args, Reply reply, long nanos, CommandOrigin origin);
}
