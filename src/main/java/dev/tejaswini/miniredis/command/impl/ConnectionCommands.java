package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.protocol.Reply;

/** PING and ECHO - the two commands every Redis client tests a connection with. */
public final class ConnectionCommands {

    private ConnectionCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("PING", false, (ctx, args) -> {
            if (args.size() > 2) {
                throw new CommandException("ERR wrong number of arguments for 'ping' command");
            }
            return args.size() == 2 ? new Reply.BulkString(args.get(1)) : new Reply.SimpleString("PONG");
        });

        executor.register("ECHO", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'echo' command");
            }
            return new Reply.BulkString(args.get(1));
        });

        // The connection loop checks for this command by name after
        // dispatch and closes the socket once the OK reply is flushed.
        executor.register("QUIT", false, (ctx, args) -> Reply.ok());
    }
}
