package dev.tejaswini.miniredis.protocol;

/** Thrown when the bytes on the wire don't form a valid RESP request. */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }
}
