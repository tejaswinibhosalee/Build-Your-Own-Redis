package dev.tejaswini.miniredis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** A hostile client must not be able to make the server allocate huge buffers. */
class RespReaderLimitsTest {

    private static RespReader reader(String raw) {
        return new RespReader(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void rejectsAbsurdArgumentCount() {
        assertThrows(ProtocolException.class, () -> reader("*2000000000\r\n").readCommand());
    }

    @Test
    void rejectsAbsurdBulkLength() {
        assertThrows(ProtocolException.class, () -> reader("*1\r\n$2000000000\r\n").readCommand());
    }

    @Test
    void rejectsNegativeBulkLength() {
        assertThrows(ProtocolException.class, () -> reader("*1\r\n$-5\r\n").readCommand());
    }

    @Test
    void rejectsEndlessLengthHeader() {
        assertThrows(ProtocolException.class, () -> reader("*" + "9".repeat(100) + "\r\n").readCommand());
    }

    @Test
    void rejectsOversizedInlineCommand() {
        String line = "SET k " + "x".repeat(RespReader.MAX_INLINE_BYTES + 10) + "\r\n";
        assertThrows(ProtocolException.class, () -> reader(line).readCommand());
    }
}
