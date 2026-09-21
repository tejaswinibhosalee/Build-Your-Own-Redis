package dev.tejaswini.miniredis;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandRegistry;
import dev.tejaswini.miniredis.command.ExpiryScheduler;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.persistence.AofLoader;
import dev.tejaswini.miniredis.persistence.AofWriter;
import dev.tejaswini.miniredis.server.MiniRedisServer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point: wires the whole server together.
 *
 * <pre>
 *   java -jar mini-redis.jar [--port 6379] [--aof-file mini-redis.aof] [--no-aof]
 * </pre>
 *
 * <p>Startup order matters here: the AOF file (if any) must be replayed
 * into the {@link Database} <i>before</i> the {@link MiniRedisServer}
 * starts accepting connections, otherwise a client could observe an empty
 * database that is still mid-restore.
 */
public final class MiniRedisApplication {

    private static final int DEFAULT_PORT = 6379; // Redis's own default port
    private static final String DEFAULT_AOF_FILE = "mini-redis.aof";

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        String aofFile = DEFAULT_AOF_FILE;
        boolean persistenceEnabled = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--aof-file" -> aofFile = args[++i];
                case "--no-aof" -> persistenceEnabled = false;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        Database database = new Database();
        AofWriter aofWriter = persistenceEnabled ? new AofWriter(Path.of(aofFile)) : null;

        CommandExecutor executor = new CommandExecutor(database, aofWriter);
        CommandRegistry.registerAll(executor);

        if (persistenceEnabled) {
            System.out.println("Replaying AOF from " + aofFile + " ...");
            AofLoader.load(Path.of(aofFile), executor);
            System.out.println("Restored " + database.keys().size() + " key(s).");
        }

        ExpiryScheduler expiryScheduler = new ExpiryScheduler(executor);
        expiryScheduler.start();

        MiniRedisServer server = new MiniRedisServer(port, executor);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            expiryScheduler.stop();
            try {
                server.stop();
            } catch (IOException ignored) {
                // best-effort on shutdown
            }
            if (aofWriter != null) {
                try {
                    aofWriter.close();
                } catch (IOException ignored) {
                    // best-effort on shutdown
                }
            }
        }, "shutdown-hook"));

        server.start();
    }
}
