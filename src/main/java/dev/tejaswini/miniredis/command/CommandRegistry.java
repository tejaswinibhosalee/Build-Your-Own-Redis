package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.command.impl.ConnectionCommands;
import dev.tejaswini.miniredis.command.impl.GenericCommands;
import dev.tejaswini.miniredis.command.impl.HashCommands;
import dev.tejaswini.miniredis.command.impl.ListCommands;
import dev.tejaswini.miniredis.command.impl.ServerCommands;
import dev.tejaswini.miniredis.command.impl.SetCommands;
import dev.tejaswini.miniredis.command.impl.StringCommands;

/** Wires every command group into one {@link CommandExecutor}. */
public final class CommandRegistry {

    private CommandRegistry() {
    }

    public static void registerAll(CommandExecutor executor) {
        ConnectionCommands.register(executor);
        GenericCommands.register(executor);
        StringCommands.register(executor);
        ListCommands.register(executor);
        HashCommands.register(executor);
        SetCommands.register(executor);
        ServerCommands.register(executor);
    }
}
