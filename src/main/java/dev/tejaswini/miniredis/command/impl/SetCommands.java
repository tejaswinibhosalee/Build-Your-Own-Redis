package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;
import java.util.Set;

/** Set commands - unordered, unique members: SADD, SMEMBERS, SREM, SISMEMBER. */
public final class SetCommands {

    private SetCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("SADD", true, (ctx, args) -> {
            if (args.size() < 3) {
                throw new CommandException("ERR wrong number of arguments for 'sadd' command");
            }
            Set<String> set = ctx.db().getOrCreateSet(args.get(1));
            long added = args.subList(2, args.size()).stream().filter(set::add).count();
            return new Reply.Integer(added);
        });

        executor.register("SMEMBERS", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'smembers' command");
            }
            Set<String> set = ctx.db().getSet(args.get(1));
            if (set == null) {
                return new Reply.Array(List.of());
            }
            return new Reply.Array(set.stream().<Reply>map(Reply.BulkString::new).toList());
        });

        executor.register("SREM", true, (ctx, args) -> {
            if (args.size() < 3) {
                throw new CommandException("ERR wrong number of arguments for 'srem' command");
            }
            Database db = ctx.db();
            String key = args.get(1);
            Set<String> set = db.getSet(key);
            if (set == null) {
                return new Reply.Integer(0);
            }
            long removed = args.subList(2, args.size()).stream().filter(set::remove).count();
            db.deleteIfEmpty(key);
            return new Reply.Integer(removed);
        });

        executor.register("SISMEMBER", false, (ctx, args) -> {
            if (args.size() != 3) {
                throw new CommandException("ERR wrong number of arguments for 'sismember' command");
            }
            Set<String> set = ctx.db().getSet(args.get(1));
            boolean isMember = set != null && set.contains(args.get(2));
            return new Reply.Integer(isMember ? 1 : 0);
        });
    }
}
