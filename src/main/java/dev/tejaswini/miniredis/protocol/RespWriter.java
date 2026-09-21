package dev.tejaswini.miniredis.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serializes a {@link Reply} into RESP bytes.
 *
 * <p>RESP frames every reply the same way: a one-byte type prefix, a
 * payload, then a trailing "\r\n". That is the entire protocol - there is
 * no schema negotiation, no headers, nothing else. Its simplicity (easy to
 * parse by hand, human-readable enough to debug over `telnet`) is a big
 * part of why Redis clients are trivial to write for every language.
 *
 * <pre>
 *   +OK\r\n                  Simple String
 *   -ERR message\r\n         Error
 *   :1000\r\n                Integer
 *   $5\r\nhello\r\n          Bulk String
 *   *2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n   Array
 * </pre>
 */
public final class RespWriter {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    private RespWriter() {
    }

    public static void write(OutputStream out, Reply reply) throws IOException {
        switch (reply) {
            case Reply.SimpleString s -> {
                out.write('+');
                out.write(bytes(s.value()));
                out.write(CRLF);
            }
            case Reply.Error e -> {
                out.write('-');
                out.write(bytes(e.message()));
                out.write(CRLF);
            }
            case Reply.Integer i -> {
                out.write(':');
                out.write(bytes(java.lang.Long.toString(i.value())));
                out.write(CRLF);
            }
            case Reply.BulkString b -> writeBulkString(out, b.value());
            case Reply.Array a -> {
                if (a.items() == null) {
                    out.write('*');
                    out.write(bytes("-1"));
                    out.write(CRLF);
                    return;
                }
                out.write('*');
                out.write(bytes(java.lang.Integer.toString(a.items().size())));
                out.write(CRLF);
                for (Reply item : a.items()) {
                    write(out, item);
                }
            }
        }
    }

    private static void writeBulkString(OutputStream out, String value) throws IOException {
        if (value == null) {
            out.write('$');
            out.write(bytes("-1"));
            out.write(CRLF);
            return;
        }
        byte[] payload = bytes(value);
        out.write('$');
        out.write(bytes(java.lang.Integer.toString(payload.length)));
        out.write(CRLF);
        out.write(payload);
        out.write(CRLF);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
