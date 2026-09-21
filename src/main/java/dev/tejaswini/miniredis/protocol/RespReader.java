package dev.tejaswini.miniredis.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads one client command at a time off a socket's {@link InputStream} and
 * turns it into a {@code List<String>} of arguments, e.g.
 * {@code SET foo bar} -&gt; {@code ["SET", "foo", "bar"]}.
 *
 * <p>Real Redis clients (redis-cli, Jedis, Lettuce, ...) send every command
 * as a RESP <b>Array of Bulk Strings</b>:
 * <pre>
 *   *3\r\n
 *   $3\r\nSET\r\n
 *   $3\r\nfoo\r\n
 *   $3\r\nbar\r\n
 * </pre>
 * the {@code *3} says "3 elements follow", each {@code $N} says "the next
 * element is N bytes". That length-prefixing is what makes RESP
 * binary-safe: a value can contain "\r\n" or NUL bytes and the parser still
 * knows exactly where it ends.
 *
 * <p>We also accept plain <b>inline commands</b> (a bare line of
 * whitespace-separated words, no {@code *}/{@code $} framing at all) purely
 * so the server can be poked with {@code telnet} or {@code nc} while
 * learning the protocol - real Redis supports this too, for the same
 * reason.
 */
public final class RespReader {

    private final InputStream in;

    public RespReader(InputStream in) {
        this.in = in;
    }

    /**
     * Reads and returns the next command's arguments, or {@code null} if
     * the client closed the connection before sending anything (a clean
     * EOF, not an error).
     */
    public List<String> readCommand() throws IOException {
        int first = in.read();
        if (first == -1) {
            return null; // client disconnected
        }
        if (first == '*') {
            return readArrayCommand();
        }
        return readInlineCommand((char) first);
    }

    private List<String> readArrayCommand() throws IOException {
        int count = (int) readLong();
        if (count <= 0) {
            return List.of();
        }
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int type = in.read();
            if (type != '$') {
                throw new ProtocolException("expected '$' bulk-string prefix, got '" + (char) type + "'");
            }
            int length = (int) readLong();
            byte[] payload = readExactly(length);
            expectCrlf();
            args.add(new String(payload, StandardCharsets.UTF_8));
        }
        return args;
    }

    /** Parses a plain-text line into whitespace-separated arguments. */
    private List<String> readInlineCommand(char firstChar) throws IOException {
        StringBuilder line = new StringBuilder();
        line.append(firstChar);
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') {
                line.append((char) b);
            }
        }
        String trimmed = line.toString().trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return List.of(trimmed.split("\\s+"));
    }

    /** Reads digits up to the terminating "\r\n" and parses them as a long. */
    private long readLong() throws IOException {
        StringBuilder digits = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\r') {
            digits.append((char) b);
        }
        expectLf();
        try {
            return Long.parseLong(digits.toString().trim());
        } catch (NumberFormatException e) {
            throw new ProtocolException("invalid length/count: '" + digits + "'");
        }
    }

    private byte[] readExactly(int length) throws IOException {
        if (length < 0) {
            throw new ProtocolException("negative bulk length: " + length);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(length);
        int remaining = length;
        byte[] chunk = new byte[Math.max(1, Math.min(length, 8192))];
        while (remaining > 0) {
            int read = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read == -1) {
                throw new ProtocolException("unexpected EOF while reading bulk string");
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return buffer.toByteArray();
    }

    private void expectCrlf() throws IOException {
        int cr = in.read();
        expectLf();
        if (cr != '\r') {
            throw new ProtocolException("expected trailing CRLF after bulk string");
        }
    }

    private void expectLf() throws IOException {
        int lf = in.read();
        if (lf != '\n') {
            throw new ProtocolException("expected LF");
        }
    }
}
