package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.protocol.Reply;

import java.util.List;

/**
 * One Redis command's logic. {@code args} is the full command line
 * including the command name itself at index 0 (e.g. {@code ["SET",
 * "foo", "bar"]}), which is how Redis documentation itself indexes
 * arguments, so command code and the protocol spec line up 1:1.
 */
@FunctionalInterface
public interface CommandHandler {

    Reply handle(CommandContext ctx, List<String> args);
}
