package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;

/**
 * Commands that work on any key regardless of what type of value it holds:
 * DEL, EXISTS, EXPIRE, TTL, PERSIST, KEYS, TYPE, FLUSHALL.
 */
public final class GenericCommands {

    private GenericCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("DEL", true, (ctx, args) -> {
            if (args.size() < 2) {
                throw new CommandException("ERR wrong number of arguments for 'del' command");
            }
            long removed = args.subList(1, args.size()).stream().filter(ctx.db()::delete).count();
            return new Reply.Integer(removed);
        });

        executor.register("EXISTS", false, (ctx, args) -> {
            if (args.size() < 2) {
                throw new CommandException("ERR wrong number of arguments for 'exists' command");
            }
            long count = args.subList(1, args.size()).stream().filter(ctx.db()::exists).count();
            return new Reply.Integer(count);
        });

        executor.register("EXPIRE", true, (ctx, args) -> {
            if (args.size() != 3) {
                throw new CommandException("ERR wrong number of arguments for 'expire' command");
            }
            Database db = ctx.db();
            String key = args.get(1);
            long seconds = parseLong(args.get(2));
            if (!db.exists(key)) {
                return new Reply.Integer(0);
            }
            db.setExpireAt(key, System.currentTimeMillis() + seconds * 1000L);
            return new Reply.Integer(1);
        });

        executor.register("TTL", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'ttl' command");
            }
            long ttlMillis = ctx.db().ttlMillis(args.get(1));
            if (ttlMillis < 0) {
                return new Reply.Integer(ttlMillis); // -2 no key, -1 no TTL
            }
            // round up so a key that expires in 900ms reports 1 second left, not 0
            return new Reply.Integer((ttlMillis + 999) / 1000);
        });

        executor.register("PERSIST", true, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'persist' command");
            }
            return new Reply.Integer(ctx.db().persist(args.get(1)) ? 1 : 0);
        });

        executor.register("KEYS", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'keys' command");
            }
            List<Reply> matches = ctx.db().keysMatching(args.get(1)).stream()
                    .<Reply>map(Reply.BulkString::new)
                    .toList();
            return new Reply.Array(matches);
        });

        executor.register("TYPE", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'type' command");
            }
            return new Reply.SimpleString(ctx.db().type(args.get(1)));
        });

        executor.register("FLUSHALL", true, (ctx, args) -> {
            ctx.db().flushAll();
            return Reply.ok();
        });
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new CommandException("ERR value is not an integer or out of range");
        }
    }
}
