package dev.tejaswini.miniredis.core;

/**
 * Thrown when a command touches a key that exists but holds the wrong data
 * type - e.g. {@code LPUSH} on a key that was created with {@code SET}.
 * Redis keeps every value type in one keyspace instead of separate
 * "string db" / "list db" / etc., so this check is how it enforces that a
 * given key only ever means one kind of thing.
 */
public class WrongTypeException extends CommandException {

    public WrongTypeException() {
        super("WRONGTYPE Operation against a key holding the wrong kind of value");
    }
}
