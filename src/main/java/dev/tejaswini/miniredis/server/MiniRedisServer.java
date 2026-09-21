package dev.tejaswini.miniredis.server;

import dev.tejaswini.miniredis.command.CommandExecutor;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 */
public final class MiniRedisServer {

    private final int port;
    private final CommandExecutor executor;
    private final ExecutorService connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ServerSocket serverSocket;

    public MiniRedisServer(int port, CommandExecutor executor) {
        this.port = port;
        this.executor = executor;
    }

    /** Blocks, accepting connections until {@link #stop()} is called from another thread. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("mini-redis listening on port " + port);

        while (!serverSocket.isClosed()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break; // stop() closed the socket while accept() was blocked
                }
                throw e;
            }
            connectionExecutor.submit(new ClientHandler(client, executor));
        }
    }

    public void stop() throws IOException {
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        connectionExecutor.shutdown();
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket != null ? socket.getLocalPort() : port;
    }
}
