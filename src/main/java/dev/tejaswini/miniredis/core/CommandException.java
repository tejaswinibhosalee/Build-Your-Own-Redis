package dev.tejaswini.miniredis.core;

/**
 * Any error a command wants reported back to the client as a RESP error
 * reply (e.g. "ERR wrong number of arguments for 'get' command"). Caught in
 * one place - {@code CommandExecutor.execute} - and turned into
 * {@code Reply.Error}, so individual command implementations can just
 * {@code throw} instead of threading error handling through every call.
 */
public class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message);
    }
}
