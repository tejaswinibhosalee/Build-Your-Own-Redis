package dev.tejaswini.miniredis.protocol;

import java.util.List;

/**
 * Every value a command can hand back to a client, modeled as RESP
 * (REdis Serialization Protocol) reply types.
 *
 * <p>Real Redis speaks RESP2/RESP3 on the wire; we implement the RESP2
 * subset, which is exactly the five types below. Using a sealed interface
 * + records means {@link RespWriter} can exhaustively switch over every
 * reply shape and the compiler will flag it if a new type is ever added
 * without updating the encoder.
 */
public sealed interface Reply {

    /** "+OK\r\n" - short, trusted, one-line strings (status replies). */
    record SimpleString(String value) implements Reply {}

    /** "-ERR message\r\n" - errors always start with '-' on the wire. */
    record Error(String message) implements Reply {}

    /** ":1000\r\n" - a signed 64-bit integer. */
    record Integer(long value) implements Reply {}

    /**
     * "$5\r\nhello\r\n" - a length-prefixed binary-safe string.
     * {@code value == null} encodes as "$-1\r\n" (a "nil" reply), which is
     * how Redis represents "key does not exist" for GET, HGET, etc.
     */
    record BulkString(String value) implements Reply {}

    /**
     * "*2\r\n...\r\n...\r\n" - an ordered list of replies (used for KEYS,
     * LRANGE, HGETALL, ...). {@code items == null} encodes as "*-1\r\n"
     * (a "nil array"), distinct from an empty array "*0\r\n".
     */
    record Array(List<Reply> items) implements Reply {}

    // --- convenience factories, so command code reads like Redis docs ---

    static Reply ok() {
        return new SimpleString("OK");
    }

    static Reply nilBulk() {
        return new BulkString(null);
    }

    static Reply nilArray() {
        return new Array(null);
    }
}
