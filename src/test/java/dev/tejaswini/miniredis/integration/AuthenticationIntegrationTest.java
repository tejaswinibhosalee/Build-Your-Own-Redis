package dev.tejaswini.miniredis.integration;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandRegistry;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.security.PasswordAuthenticator;
import dev.tejaswini.miniredis.server.MiniRedisServer;
import dev.tejaswini.miniredis.stats.ServerStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A password-protected server must refuse everything until AUTH succeeds. */
class AuthenticationIntegrationTest {

    private static final String PASSWORD = "test-password-not-a-real-secret";

    private MiniRedisServer server;
    private Thread serverThread;
    private ServerStats stats;

    @BeforeEach
    void startServer() throws Exception {
        CommandExecutor executor = new CommandExecutor(new Database(), null);
        CommandRegistry.registerAll(executor);
        stats = new ServerStats();
        executor.addListener(stats);
        server = new MiniRedisServer("127.0.0.1", 0, executor,
                PasswordAuthenticator.withPassword(PASSWORD), stats, 100);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
                // expected on stop()
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        serverThread.join(1000);
    }

    @Test
    void commandsAreRejectedUntilAuthenticated() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            send(out, "SET k v");
            assertEquals("-NOAUTH Authentication required.", in.readLine());

            send(out, "AUTH wrong-password");
            assertTrue(in.readLine().startsWith("-WRONGPASS"));

            send(out, "AUTH " + PASSWORD);
            assertEquals("+OK", in.readLine());

            send(out, "SET k v");
            assertEquals("+OK", in.readLine());
        }
    }

    @Test
    void acceptsRedis6StyleAuthWithDefaultUser() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            send(out, "AUTH someone-else " + PASSWORD);
            assertTrue(in.readLine().startsWith("-WRONGPASS"));

            send(out, "AUTH default " + PASSWORD);
            assertEquals("+OK", in.readLine());
        }
    }

    @Test
    void passwordNeverAppearsInTheActivityFeed() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            send(out, "AUTH " + PASSWORD);
            in.readLine();
            send(out, "PING");
            in.readLine();
        }
        stats.activitySince(0).forEach(a -> assertTrue(!a.argsPreview().contains(PASSWORD)
                && !a.command().equals("AUTH"), "AUTH must never be recorded"));
    }

    private static void send(OutputStream out, String inlineCommand) throws IOException {
        out.write((inlineCommand + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
