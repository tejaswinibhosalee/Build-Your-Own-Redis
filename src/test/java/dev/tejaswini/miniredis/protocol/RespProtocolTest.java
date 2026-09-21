package dev.tejaswini.miniredis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespProtocolTest {

    @Test
    void writesSimpleStringLikeRealRedis() throws Exception {
        assertEquals("+OK\r\n", encode(new Reply.SimpleString("OK")));
    }

    @Test
    void writesErrorWithLeadingDash() throws Exception {
        assertEquals("-ERR boom\r\n", encode(new Reply.Error("ERR boom")));
    }

    @Test
    void writesIntegerReply() throws Exception {
        assertEquals(":42\r\n", encode(new Reply.Integer(42)));
    }

    @Test
    void writesBulkStringWithLengthPrefix() throws Exception {
        assertEquals("$5\r\nhello\r\n", encode(new Reply.BulkString("hello")));
    }

    @Test
    void writesNilBulkStringAsMinusOne() throws Exception {
        assertEquals("$-1\r\n", encode(Reply.nilBulk()));
    }

    @Test
    void writesNilArrayDistinctFromEmptyArray() throws Exception {
        assertEquals("*-1\r\n", encode(Reply.nilArray()));
        assertEquals("*0\r\n", encode(new Reply.Array(List.of())));
    }

    @Test
    void writesNestedArrayOfBulkStrings() throws Exception {
        Reply reply = new Reply.Array(List.of(new Reply.BulkString("foo"), new Reply.BulkString("bar")));
        assertEquals("*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n", encode(reply));
    }

    @Test
    void parsesRespArrayCommandLikeARealClientSends() throws Exception {
        String wire = "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n";
        List<String> args = new RespReader(input(wire)).readCommand();
        assertEquals(List.of("SET", "foo", "bar"), args);
    }

    @Test
    void parsesInlineCommandForTelnetStyleUsage() throws Exception {
        String wire = "PING\r\n";
        List<String> args = new RespReader(input(wire)).readCommand();
        assertEquals(List.of("PING"), args);
    }

    @Test
    void returnsNullOnCleanEof() throws Exception {
        List<String> args = new RespReader(input("")).readCommand();
        assertNull(args);
    }

    @Test
    void bulkStringsAreBinarySafeAcrossEmbeddedCrlf() throws Exception {
        String value = "line1\r\nline2";
        Reply.BulkString reply = new Reply.BulkString(value);
        String wire = encode(reply);
        // parse it back the way a real client would, to prove length-prefixing works
        List<String> args = new RespReader(input("*1\r\n$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n")).readCommand();
        assertEquals(List.of(value), args);
        assertTrue(wire.contains(value));
    }

    @Test
    void malformedLengthPrefixThrowsProtocolException() {
        String wire = "*1\r\n$notanumber\r\nx\r\n";
        assertThrows(ProtocolException.class, () -> new RespReader(input(wire)).readCommand());
    }

    private static String encode(Reply reply) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespWriter.write(out, reply);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static ByteArrayInputStream input(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
