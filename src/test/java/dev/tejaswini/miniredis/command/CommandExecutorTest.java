package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests through the real command dispatch table, no AOF (persistence disabled). */
class CommandExecutorTest {

    private CommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CommandExecutor(new Database(), null);
        CommandRegistry.registerAll(executor);
    }

    private Reply run(String... args) {
        return executor.execute(List.of(args));
    }

    @Test
    void pingRepliesWithPong() {
        assertEquals(new Reply.SimpleString("PONG"), run("PING"));
    }

    @Test
    void unknownCommandIsAnErrorNotACrash() {
        Reply reply = run("NOTACOMMAND");
        assertInstanceOf(Reply.Error.class, reply);
        assertTrue(((Reply.Error) reply).message().contains("unknown command"));
    }

    @Test
    void setThenGetRoundTrips() {
        assertEquals(Reply.ok(), run("SET", "k", "v"));
        assertEquals(new Reply.BulkString("v"), run("GET", "k"));
    }

    @Test
    void getOnMissingKeyIsNilBulk() {
        assertEquals(Reply.nilBulk(), run("GET", "nope"));
    }

    @Test
    void setNxFailsWhenKeyAlreadyExists() {
        run("SET", "k", "v1");
        assertEquals(Reply.nilBulk(), run("SET", "k", "v2", "NX"));
        assertEquals(new Reply.BulkString("v1"), run("GET", "k"));
    }

    @Test
    void setXxOnlySucceedsWhenKeyAlreadyExists() {
        assertEquals(Reply.nilBulk(), run("SET", "k", "v", "XX"));
        assertEquals(0, ((Reply.Integer) run("EXISTS", "k")).value());
    }

    @Test
    void incrOnFreshKeyStartsFromZero() {
        assertEquals(new Reply.Integer(1), run("INCR", "counter"));
        assertEquals(new Reply.Integer(2), run("INCR", "counter"));
        assertEquals(new Reply.Integer(1), run("DECR", "counter"));
    }

    @Test
    void incrOnNonIntegerValueIsAnError() {
        run("SET", "k", "not-a-number");
        assertInstanceOf(Reply.Error.class, run("INCR", "k"));
    }

    @Test
    void appendGrowsAStringAndReturnsNewLength() {
        run("SET", "k", "Hello");
        assertEquals(new Reply.Integer(11), run("APPEND", "k", " World"));
        assertEquals(new Reply.BulkString("Hello World"), run("GET", "k"));
    }

    @Test
    void wrongTypeErrorSurfacesThroughDispatch() {
        run("SET", "k", "v");
        Reply reply = run("LPUSH", "k", "x");
        assertInstanceOf(Reply.Error.class, reply);
        assertTrue(((Reply.Error) reply).message().startsWith("WRONGTYPE"));
    }

    @Test
    void listPushPopAndRangeBehaveLikeRedis() {
        run("RPUSH", "list", "a", "b", "c");
        run("LPUSH", "list", "z");
        assertEquals(new Reply.Array(List.of(
                new Reply.BulkString("z"), new Reply.BulkString("a"),
                new Reply.BulkString("b"), new Reply.BulkString("c"))),
                run("LRANGE", "list", "0", "-1"));
        assertEquals(new Reply.BulkString("z"), run("LPOP", "list"));
        assertEquals(new Reply.BulkString("c"), run("RPOP", "list"));
    }

    @Test
    void hashSetGetAllAndDelete() {
        run("HSET", "h", "name", "Tejaswini", "role", "engineer");
        assertEquals(new Reply.BulkString("Tejaswini"), run("HGET", "h", "name"));
        assertEquals(new Reply.Integer(1), run("HDEL", "h", "role"));
        assertEquals(new Reply.Integer(0), run("HDEL", "h", "role"));
    }

    @Test
    void setAddMembersAndCheckMembership() {
        run("SADD", "s", "a", "b", "a");
        assertEquals(new Reply.Integer(1), run("SISMEMBER", "s", "a"));
        assertEquals(new Reply.Integer(0), run("SISMEMBER", "s", "z"));
    }

    @Test
    void expireAndTtlAgree() {
        run("SET", "k", "v");
        run("EXPIRE", "k", "100");
        long ttl = ((Reply.Integer) run("TTL", "k")).value();
        assertTrue(ttl > 0 && ttl <= 100);
    }

    @Test
    void delRemovesKeysAndReportsHowManyExisted() {
        run("SET", "a", "1");
        run("SET", "b", "2");
        assertEquals(new Reply.Integer(2), run("DEL", "a", "b", "c"));
    }

    @Test
    void flushAllEmptiesTheKeyspace() {
        run("SET", "a", "1");
        run("FLUSHALL");
        assertEquals(new Reply.Integer(0), run("EXISTS", "a"));
    }

    @Test
    void writeCommandIsLoggedToAofButReadCommandIsNot() throws Exception {
        java.nio.file.Path aofPath = java.nio.file.Files.createTempFile("mini-redis-test", ".aof");
        aofPath.toFile().deleteOnExit();
        try (dev.tejaswini.miniredis.persistence.AofWriter aof = new dev.tejaswini.miniredis.persistence.AofWriter(aofPath)) {
            CommandExecutor withAof = new CommandExecutor(new Database(), aof);
            CommandRegistry.registerAll(withAof);
            withAof.execute(List.of("SET", "k", "v"));
            withAof.execute(List.of("GET", "k"));
        }
        String logged = java.nio.file.Files.readString(aofPath);
        assertTrue(logged.contains("SET"));
        assertTrue(!logged.contains("GET"), "read-only commands must not be persisted");
    }
}
