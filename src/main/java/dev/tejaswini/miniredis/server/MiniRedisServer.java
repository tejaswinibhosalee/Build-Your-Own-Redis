package dev.tejaswini.miniredis.server;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.security.LoginThrottle;
import dev.tejaswini.miniredis.security.PasswordAuthenticator;
import dev.tejaswini.miniredis.stats.ServerStats;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The accept loop: owns the listening socket and, for every incoming
 * connection, hands it off to its own {@link ClientHandler} running on a
 * <b>virtual thread</b> (Project Loom, standard since Java 21).
 *
 * <p>Why virtual threads instead of a fixed-size platform-thread pool? A
 * Redis-like server's connections spend almost all their time blocked
 * waiting on socket I/O, not doing CPU work. A platform thread blocked on
 * a socket read still occupies a full OS thread and its stack (~1MB); a
 * blocked virtual thread costs only a small heap object, so tens of
 * thousands of idle-but-open connections are cheap - no thread-pool sizing
 * to tune, no connection queueing. Command *execution* is still safely
 * serialized (see {@link CommandExecutor}), so more connections means more
 * concurrent I/O, not more concurrent mutation of the keyspace.
 *
 * <h2>Network security</h2>
 * <ul>
 *   <li><b>Loopback by default.</b> The default bind address is
 *       {@code 127.0.0.1}, so only programs on the same machine can connect
 *       unless you explicitly opt in to more.</li>
 *   <li><b>Protected mode.</b> Copied from real Redis: if no password is
 *       configured, connections from any non-loopback address are refused
 *       with a {@code -DENIED} error, even if you bound to {@code 0.0.0.0}
 *       by mistake.</li>
 *   <li><b>Connection limit.</b> At most {@code maxClients} sockets are
 *       served at once, so a flood of connections can't exhaust memory.</li>
 * </ul>
 */
public final class MiniRedisServer {

    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_MAX_CLIENTS = 10_000;

    private static final byte[] DENIED_REPLY = ("-DENIED mini-redis is running in protected mode: no password is set "
            + "and the connection comes from a non-loopback address. Set MINI_REDIS_PASSWORD to allow remote "
            + "clients, or connect from localhost.\r\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] MAX_CLIENTS_REPLY =
            "-ERR max number of clients reached\r\n".getBytes(StandardCharsets.UTF_8);

    private final String bindAddress;
    private final int port;
    private final CommandExecutor executor;
    private final PasswordAuthenticator authenticator;
    private final ServerStats stats;
    private final int maxClients;
    private final LoginThrottle loginThrottle = new LoginThrottle();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ExecutorService connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ServerSocket serverSocket;

    /** Loopback-only, no password, fresh stats: handy for tests. */
    public MiniRedisServer(int port, CommandExecutor executor) {
        this(DEFAULT_BIND_ADDRESS, port, executor, PasswordAuthenticator.disabled(), new ServerStats(),
                DEFAULT_MAX_CLIENTS);
    }

    public MiniRedisServer(String bindAddress, int port, CommandExecutor executor,
                           PasswordAuthenticator authenticator, ServerStats stats, int maxClients) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.executor = executor;
        this.authenticator = authenticator;
        this.stats = stats;
        this.maxClients = maxClients;
    }

    /** Blocks, accepting connections until {@link #stop()} is called from another thread. */
    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
        serverSocket = socket;
        System.out.println("mini-redis listening on " + bindAddress + ":" + getPort()
                + (authenticator.isEnabled() ? " (password required)" : " (no password: loopback clients only)"));

        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (socket.isClosed()) {
                    break; // stop() closed the socket while accept() was blocked
                }
                throw e;
            }
            handle(client);
        }
    }

    private void handle(Socket client) {
        if (!authenticator.isEnabled() && !isLoopback(client)) {
            rejectAndClose(client, DENIED_REPLY);
            return;
        }
        if (activeConnections.incrementAndGet() > maxClients) {
            activeConnections.decrementAndGet();
            rejectAndClose(client, MAX_CLIENTS_REPLY);
            return;
        }
        stats.clientConnected();
        ClientHandler handler = new ClientHandler(client, executor, authenticator, loginThrottle);
        connectionExecutor.submit(() -> {
            try {
                handler.run();
            } finally {
                activeConnections.decrementAndGet();
                stats.clientDisconnected();
            }
        });
    }

    static boolean isLoopback(Socket client) {
        InetAddress address = client.getInetAddress();
        return address != null && address.isLoopbackAddress();
    }

    private void rejectAndClose(Socket client, byte[] reply) {
        stats.connectionRejected();
        try (Socket ignored = client) {
            OutputStream out = client.getOutputStream();
            out.write(reply);
            out.flush();
        } catch (IOException ignored) {
            // the client is being turned away anyway
        }
    }

    public void stop() throws IOException {
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        connectionExecutor.shutdown();
    }

    /** The actual bound port (useful when started with port 0), or the configured port before start. */
    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket != null && socket.isBound() ? socket.getLocalPort() : port;
    }
}
