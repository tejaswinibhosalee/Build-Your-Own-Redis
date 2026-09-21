package dev.tejaswini.miniredis.command.impl;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.core.CommandException;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * List commands, backed by {@link java.util.ArrayDeque}: LPUSH, RPUSH,
 * LPOP, RPOP, LRANGE, LLEN. The deque's head is the list's left/first
 * element, its tail is the list's right/last element - the same mental
 * model Redis uses, which is why the commands are named "Left"/"Right".
 */
public final class ListCommands {

    private ListCommands() {
    }

    public static void register(CommandExecutor executor) {
        executor.register("LPUSH", true, (ctx, args) -> push(ctx.db(), args, true));
        executor.register("RPUSH", true, (ctx, args) -> push(ctx.db(), args, false));
        executor.register("LPOP", true, (ctx, args) -> pop(ctx.db(), args, true));
        executor.register("RPOP", true, (ctx, args) -> pop(ctx.db(), args, false));
        executor.register("LLEN", false, (ctx, args) -> {
            if (args.size() != 2) {
                throw new CommandException("ERR wrong number of arguments for 'llen' command");
            }
            Deque<String> list = ctx.db().getList(args.get(1));
            return new Reply.Integer(list == null ? 0 : list.size());
        });
        executor.register("LRANGE", false, (ctx, args) -> lrange(ctx.db(), args));
    }

    private static Reply push(Database db, List<String> args, boolean left) {
        if (args.size() < 3) {
            throw new CommandException("ERR wrong number of arguments for 'lpush/rpush' command");
        }
        Deque<String> list = db.getOrCreateList(args.get(1));
        for (String value : args.subList(2, args.size())) {
            if (left) list.addFirst(value); else list.addLast(value);
        }
        return new Reply.Integer(list.size());
    }

    private static Reply pop(Database db, List<String> args, boolean left) {
        if (args.size() != 2) {
            throw new CommandException("ERR wrong number of arguments for 'lpop/rpop' command");
        }
        String key = args.get(1);
        Deque<String> list = db.getList(key);
        if (list == null || list.isEmpty()) {
            return Reply.nilBulk();
        }
        String value = left ? list.pollFirst() : list.pollLast();
        db.deleteIfEmpty(key); // an empty list is not a list, same as real Redis
        return new Reply.BulkString(value);
    }

    /** LRANGE key start stop - inclusive bounds, negative indexes count from the end (-1 = last element). */
    private static Reply lrange(Database db, List<String> args) {
        if (args.size() != 4) {
            throw new CommandException("ERR wrong number of arguments for 'lrange' command");
        }
        Deque<String> list = db.getList(args.get(1));
        if (list == null || list.isEmpty()) {
            return new Reply.Array(List.of());
        }
        List<String> snapshot = new ArrayList<>(list);
        int size = snapshot.size();
        int start = normalizeIndex(parseInt(args.get(2)), size);
        int stop = normalizeIndex(parseInt(args.get(3)), size);
        start = Math.max(start, 0);
        stop = Math.min(stop, size - 1);
        if (start > stop) {
            return new Reply.Array(List.of());
        }
        List<Reply> items = snapshot.subList(start, stop + 1).stream()
                .<Reply>map(Reply.BulkString::new)
                .toList();
        return new Reply.Array(items);
    }

    private static int normalizeIndex(int index, int size) {
        return index < 0 ? size + index : index;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CommandException("ERR value is not an integer or out of range");
        }
    }
}
