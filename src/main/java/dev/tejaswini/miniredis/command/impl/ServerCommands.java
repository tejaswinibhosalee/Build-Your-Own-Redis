package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;

/**
 * Server/introspection commands. {@code COMMAND} and {@code CONFIG} are
 * stubbed out purely so that real clients - {@code redis-cli} in
 * particular, which probes both on connect to build its autocomplete -
 * don't choke talking to this server; we don't implement the actual
 * command catalog or configuration system they represent.
 */
public final class ServerCommands {

    private ServerCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("DBSIZE", false, (ctx, args) -> new Reply.Integer(ctx.db().keys().size()));

        executor.register("COMMAND", false, (ctx, args) -> new Reply.Array(List.of()));

        executor.register("CONFIG", false, (ctx, args) -> {
            // Real CONFIG GET returns [name, value, name, value, ...]; an empty
            // array is a valid (if uninteresting) answer that keeps clients happy.
            return new Reply.Array(List.of());
        });

        executor.register("INFO", false, (ctx, args) -> new Reply.BulkString(
                "# Server\r\nredis_version:mini-redis-1.0\r\n# Keyspace\r\ndb0:keys=" + ctx.db().keys().size() + "\r\n"));

        // isWrite=false: SAVE mutates the AOF *file*, not the keyspace, so it
        // must never itself be logged into (and later replayed from) the AOF -
        // replaying a SAVE while loading the AOF would try to rewrite the very
        // file being read.
        executor.register("SAVE", false, (ctx, args) -> {
            if (ctx.aof() == null) {
                throw new CommandException("ERR persistence is disabled for this server (started without an AOF file)");
            }
            ctx.aof().rewrite(ctx.db());
            return Reply.ok();
        });
    }
}
