package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;
import java.util.Map;

/** Hash commands - a hash is Redis's "object with fields", e.g. modeling a user record under one key: HSET, HGET, HGETALL, HDEL. */
public final class HashCommands {

    private HashCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("HSET", true, (ctx, args) -> {
            if (args.size() < 4 || args.size() % 2 != 0) {
                throw new CommandException("ERR wrong number of arguments for 'hset' command");
            }
            Map<String, String> hash = ctx.db().getOrCreateHash(args.get(1));
            int added = 0;
            for (int i = 2; i < args.size(); i += 2) {
                if (hash.put(args.get(i), args.get(i + 1)) == null) {
                    added++;
                }
            }
            return new Reply.Integer(added);
        });

        executor.register("HGET", false, (ctx, args) -> {
            if (args.size() != 3) {
                throw new CommandException("ERR wrong number of arguments for 'hget' command");
            }
            Map<String, String> hash = ctx.db().getHash(args.get(1));
            String value = hash == null ? null : hash.get(args.get(2));
            return new Reply.BulkString(value);
        });

        executor.register("HGETALL", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'hgetall' command");
            }
            Map<String, String> hash = ctx.db().getHash(args.get(1));
            if (hash == null) {
                return new Reply.Array(List.of());
            }
            List<Reply> flattened = hash.entrySet().stream()
                    .<Reply>mapMulti((entry, consumer) -> {
                        consumer.accept(new Reply.BulkString(entry.getKey()));
                        consumer.accept(new Reply.BulkString(entry.getValue()));
                    })
                    .toList();
            return new Reply.Array(flattened);
        });

        executor.register("HDEL", true, (ctx, args) -> {
            if (args.size() < 3) {
                throw new CommandException("ERR wrong number of arguments for 'hdel' command");
            }
            Database db = ctx.db();
            String key = args.get(1);
            Map<String, String> hash = db.getHash(key);
            if (hash == null) {
                return new Reply.Integer(0);
            }
            long removed = args.subList(2, args.size()).stream().filter(field -> hash.remove(field) != null).count();
            db.deleteIfEmpty(key);
            return new Reply.Integer(removed);
        });
    }
}
