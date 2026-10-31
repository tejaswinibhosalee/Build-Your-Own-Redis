package dev.tejaswini.miniredis;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandRegistry;
import dev.tejaswini.miniredis.command.ExpiryScheduler;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.dashboard.DashboardApi;
import dev.tejaswini.miniredis.dashboard.DashboardServer;
import dev.tejaswini.miniredis.persistence.AofLoader;
import dev.tejaswini.miniredis.persistence.AofWriter;
import dev.tejaswini.miniredis.security.PasswordAuthenticator;
import dev.tejaswini.miniredis.server.MiniRedisServer;
import dev.tejaswini.miniredis.stats.ServerStats;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Entry point: parses options and wires the whole server together.
 *
 * <p>Startup order matters here: the AOF file (if any) must be replayed
 * into the {@link Database} <i>before</i> the {@link MiniRedisServer}
 * starts accepting connections, otherwise a client could observe an empty
 * database that is still mid-restore.
 */
public final class MiniRedisApplication {

    public static final String VERSION = "1.1.0";

    private static final String USAGE = """
            mini-redis %s - a from-scratch Redis server with a live web dashboard

            Usage: java -jar mini-redis.jar [options]

            Server
              --port <n>               TCP port for Redis clients            (default 6379)
              --bind <address>         address to listen on                  (default 127.0.0.1)
              --max-clients <n>        max simultaneous client connections   (default 10000)

            Persistence
              --aof-file <path>        append-only file location             (default mini-redis.aof)
              --no-aof                 keep data in memory only

            Dashboard
              --dashboard-port <n>     web dashboard port                    (default 8080)
              --dashboard-bind <addr>  dashboard listen address              (default 127.0.0.1)
              --no-dashboard           don't start the web dashboard

            Security
              --password-file <path>   read the password from a file
              The password can also be supplied via the %s
              environment variable. It is deliberately NOT accepted as a command-line
              flag, because command lines are visible to every user via `ps`.

              --help                   show this message
            """;

    private record Options(int port, String bind, int maxClients, String aofFile, boolean aofEnabled,
                           int dashboardPort, String dashboardBind, boolean dashboardEnabled,
                           Optional<Path> passwordFile) {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with --help to see all options.");
            System.exit(2);
            return;
        }
        if (options == null) {
            System.out.printf(USAGE, VERSION, PasswordAuthenticator.PASSWORD_ENV_VAR);
            return;
        }
        try {
            run(options);
        } catch (IllegalStateException | IllegalArgumentException | IOException e) {
            System.err.println("Failed to start: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Options o) throws IOException {
        PasswordAuthenticator auth = PasswordAuthenticator.fromEnvironmentOrFile(o.passwordFile());
        printSecurityWarnings(o, auth);

        Database database = new Database();
        AofWriter aofWriter = o.aofEnabled() ? new AofWriter(Path.of(o.aofFile())) : null;

        CommandExecutor executor = new CommandExecutor(database, aofWriter);
        CommandRegistry.registerAll(executor);

        if (o.aofEnabled()) {
            System.out.println("Replaying AOF from " + o.aofFile() + " ...");
            AofLoader.load(Path.of(o.aofFile()), executor);
            System.out.println("Restored " + database.keys().size() + " key(s).");
        }

        // Attach metrics only after replay, so restored commands don't count as live traffic.
        ServerStats stats = new ServerStats();
        executor.addListener(stats);

        ExpiryScheduler expiryScheduler = new ExpiryScheduler(executor);
        expiryScheduler.start();

        MiniRedisServer server = new MiniRedisServer(o.bind(), o.port(), executor, auth, stats, o.maxClients());

        DashboardServer dashboard = null;
        if (o.dashboardEnabled()) {
            dashboard = new DashboardServer(o.dashboardBind(), o.dashboardPort(), executor, stats, auth,
                    new DashboardApi.ServerInfo(VERSION, o.bind(), o.port(), o.aofEnabled()));
            dashboard.start();
        }

        DashboardServer dashboardToStop = dashboard;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            expiryScheduler.stop();
            if (dashboardToStop != null) {
                dashboardToStop.stop();
            }
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

    private static void printSecurityWarnings(Options o, PasswordAuthenticator auth) throws IOException {
        boolean exposed = !InetAddress.getByName(o.bind()).isLoopbackAddress();
        if (exposed && !auth.isEnabled()) {
            System.out.println("WARNING: listening on " + o.bind() + " without a password. Protected mode is ON: "
                    + "only loopback clients will be accepted. Set " + PasswordAuthenticator.PASSWORD_ENV_VAR
                    + " to allow remote clients.");
        }
        if (auth.isEnabled()) {
            System.out.println("Password authentication is enabled.");
        }
    }

    /** @return parsed options, or {@code null} if --help was requested. */
    static Options parse(String[] args) {
        int port = 6379;
        String bind = MiniRedisServer.DEFAULT_BIND_ADDRESS;
        int maxClients = MiniRedisServer.DEFAULT_MAX_CLIENTS;
        String aofFile = "mini-redis.aof";
        boolean aofEnabled = true;
        int dashboardPort = DashboardServer.DEFAULT_PORT;
        String dashboardBind = DashboardServer.DEFAULT_BIND_ADDRESS;
        boolean dashboardEnabled = true;
        Optional<Path> passwordFile = Optional.empty();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    return null;
                }
                case "--port" -> port = parsePort(value(args, ++i, arg));
                case "--bind" -> bind = value(args, ++i, arg);
                case "--max-clients" -> maxClients = parsePositive(value(args, ++i, arg), arg);
                case "--aof-file" -> aofFile = value(args, ++i, arg);
                case "--no-aof" -> aofEnabled = false;
                case "--dashboard-port" -> dashboardPort = parsePort(value(args, ++i, arg));
                case "--dashboard-bind" -> dashboardBind = value(args, ++i, arg);
                case "--no-dashboard" -> dashboardEnabled = false;
                case "--password-file" -> passwordFile = Optional.of(Path.of(value(args, ++i, arg)));
                case "--requirepass", "--password" -> throw new IllegalArgumentException(arg
                        + " is not supported because command-line arguments are visible to other users. Use the "
                        + PasswordAuthenticator.PASSWORD_ENV_VAR + " environment variable or --password-file.");
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        return new Options(port, bind, maxClients, aofFile, aofEnabled, dashboardPort, dashboardBind,
                dashboardEnabled, passwordFile);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static int parsePort(String value) {
        int port = parsePositive(value, "port");
        if (port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + value);
        }
        return port;
    }

    private static int parsePositive(String value, String what) {
        try {
            int n = Integer.parseInt(value);
            if (n <= 0) {
                throw new IllegalArgumentException(what + " must be positive: " + value);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a number: " + value);
        }
    }
}
