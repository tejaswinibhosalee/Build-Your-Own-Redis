package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandContext;
import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;
import java.util.Locale;

/** String commands: SET, GET, INCR, DECR, APPEND, STRLEN. */
public final class StringCommands {

    private StringCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("SET", true, StringCommands::set);
        executor.register("GET", false, StringCommands::get);
        executor.register("INCR", true, (ctx, args) -> incrBy(ctx.db(), args, 1));
        executor.register("DECR", true, (ctx, args) -> incrBy(ctx.db(), args, -1));
        executor.register("APPEND", true, StringCommands::append);
        executor.register("STRLEN", false, StringCommands::strlen);
    }

    /**
     * SET key value [EX seconds | PX milliseconds] [NX | XX]
     *
     * <p>NX ("only if it does Not eXist") and XX ("only if it already
     * eXists") are what make SET usable as a distributed-lock primitive in
     * real applications - {@code SET lock:order-42 <owner> NX EX 30} either
     * acquires a 30-second lock or fails atomically, with no separate
     * "check then set" race condition possible because the whole thing
     * runs as one command while our global command lock is held.
     */
    private static Reply set(CommandContext ctx, List<String> args) {
        if (args.size() < 3) {
            throw new CommandException("ERR wrong number of arguments for 'set' command");
        }
        Database db = ctx.db();
        String key = args.get(1);
        String value = args.get(2);

        Long expireAtMillis = null;
        boolean nx = false;
        boolean xx = false;

        for (int i = 3; i < args.size(); i++) {
            String option = args.get(i).toUpperCase(Locale.ROOT);
            switch (option) {
                case "EX" -> expireAtMillis = System.currentTimeMillis() + parseUnit(args, ++i) * 1000L;
                case "PX" -> expireAtMillis = System.currentTimeMillis() + parseUnit(args, ++i);
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                default -> throw new CommandException("ERR syntax error");
            }
        }

        boolean exists = db.exists(key);
        if ((nx && exists) || (xx && !exists)) {
            return Reply.nilBulk();
        }

        db.setString(key, value);
        if (expireAtMillis != null) {
            db.setExpireAt(key, expireAtMillis);
        }
        return Reply.ok();
    }

    private static long parseUnit(List<String> args, int index) {
        if (index >= args.size()) {
            throw new CommandException("ERR syntax error");
        }
        try {
            return Long.parseLong(args.get(index));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR value is not an integer or out of range");
        }
    }

    private static Reply get(CommandContext ctx, List<String> args) {
        if (args.size() != 2) {
            throw new CommandException("ERR wrong number of arguments for 'get' command");
        }
        return new Reply.BulkString(ctx.db().getString(args.get(1)));
    }

    private static Reply incrBy(Database db, List<String> args, long delta) {
        if (args.size() != 2) {
            throw new CommandException("ERR wrong number of arguments for 'incr/decr' command");
        }
        String key = args.get(1);
        String current = db.getString(key);
        long value;
        try {
            value = (current == null ? 0L : Long.parseLong(current)) + delta;
        } catch (NumberFormatException e) {
            throw new CommandException("ERR value is not an integer or out of range");
        }
        db.setString(key, Long.toString(value));
        return new Reply.Integer(value);
    }

    private static Reply append(CommandContext ctx, List<String> args) {
        if (args.size() != 3) {
            throw new CommandException("ERR wrong number of arguments for 'append' command");
        }
        Database db = ctx.db();
        String key = args.get(1);
        String suffix = args.get(2);
        String current = db.getString(key);
        String updated = (current == null ? "" : current) + suffix;
        db.setString(key, updated);
        return new Reply.Integer(updated.length());
    }

    private static Reply strlen(CommandContext ctx, List<String> args) {
        if (args.size() != 2) {
            throw new CommandException("ERR wrong number of arguments for 'strlen' command");
        }
        String value = ctx.db().getString(args.get(1));
        return new Reply.Integer(value == null ? 0 : value.length());
    }
}
