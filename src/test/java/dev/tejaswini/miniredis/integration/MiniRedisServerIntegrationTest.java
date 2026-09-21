package dev.tejaswini.miniredis.integration;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandRegistry;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.server.MiniRedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the server exactly like a real client would: opens a raw TCP
 * socket and writes actual RESP bytes, instead of calling Java methods
 * directly. This is the test that proves the whole stack - accept loop,
 * per-connection virtual thread, RESP parsing, command dispatch, RESP
 * encoding - actually works together end-to-end.
 */
class MiniRedisServerIntegrationTest {

    private MiniRedisServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        CommandExecutor executor = new CommandExecutor(new Database(), null);
        CommandRegistry.registerAll(executor);
        server = new MiniRedisServer(0, executor); // port 0 = let the OS assign a free port
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
                // expected once stop() closes the listening socket
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(server.getPort() > 0, "server failed to start in time");
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        serverThread.join(1000);
    }

    @Test
    void respondsToRespEncodedSetAndGet() throws Exception {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(resp("SET", "foo", "bar"));
            out.flush();
            assertEquals("+OK\r\n", readReply(in));

            out.write(resp("GET", "foo"));
            out.flush();
            assertEquals("$3\r\nbar\r\n", readReply(in));
        }
    }

    @Test
    void respondsToInlinePingLikeTelnetWould() throws Exception {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("PING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertEquals("+PONG\r\n", readReply(in));
        }
    }

    @Test
    void handlesConcurrentClientsWithoutCorruptingASharedCounter() throws Exception {
        int clientCount = 10;
        int incrementsPerClient = 50;
        Thread[] threads = new Thread[clientCount];
        for (int i = 0; i < clientCount; i++) {
            threads[i] = new Thread(() -> {
                try (Socket socket = new Socket("localhost", server.getPort())) {
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();
                    for (int j = 0; j < incrementsPerClient; j++) {
                        out.write(resp("INCR", "shared-counter"));
                        out.flush();
                        readReply(in);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        try (Socket socket = new Socket("localhost", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(resp("GET", "shared-counter"));
            out.flush();
            int expected = clientCount * incrementsPerClient;
            assertEquals("$" + Integer.toString(expected).length() + "\r\n" + expected + "\r\n", readReply(in));
        }
    }

    private static byte[] resp(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n").append(arg).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Reads exactly one RESP reply off the stream, the same way a minimal real client parser would. */
    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int type = in.read();
        buffer.write(type);
        if (type == '+' || type == '-' || type == ':') {
            copyLine(in, buffer);
        } else if (type == '$') {
            int length = Integer.parseInt(readLine(in, buffer).trim());
            if (length >= 0) {
                buffer.write(in.readNBytes(length));
                buffer.write(in.read());
                buffer.write(in.read());
            }
        } else if (type == '*') {
            copyLine(in, buffer); // this test only exercises flat replies (GET/SET/PING/INCR)
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void copyLine(InputStream in, ByteArrayOutputStream buffer) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            if (b == '\n') {
                break;
            }
        }
    }

    private static String readLine(InputStream in, ByteArrayOutputStream buffer) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.append((char) b);
            }
        }
        return line.toString();
    }
}
