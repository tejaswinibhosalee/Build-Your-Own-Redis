package dev.tejaswini.miniredis.dashboard;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandRegistry;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.security.PasswordAuthenticator;
import dev.tejaswini.miniredis.stats.ServerStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the dashboard's HTTP security rules against a real running server. */
class DashboardServerTest {

    private static final String PASSWORD = "dashboard-test-password";
    private final HttpClient http = HttpClient.newHttpClient();
    private DashboardServer dashboard;
    private String base;

    private void start(PasswordAuthenticator auth, String bind) throws Exception {
        CommandExecutor executor = new CommandExecutor(new Database(), null);
        CommandRegistry.registerAll(executor);
        ServerStats stats = new ServerStats();
        executor.addListener(stats);
        dashboard = new DashboardServer(bind, 0, executor, stats, auth,
                new DashboardApi.ServerInfo("test", "127.0.0.1", 6379, false));
        dashboard.start();
        base = "http://localhost:" + dashboard.getPort();
    }

    @AfterEach
    void stop() {
        if (dashboard != null) {
            dashboard.stop();
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token, boolean csrfHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrfHeader) b.header(DashboardServer.CSRF_HEADER, DashboardServer.CSRF_HEADER_VALUE);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesUiWithSecurityHeaders() throws Exception {
        start(PasswordAuthenticator.disabled(), "127.0.0.1");
        HttpResponse<String> res = get("/", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("<div id=\"root\">"));
        assertTrue(res.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(""));
    }

    @Test
    void blocksPathTraversalAndUnknownFiles() throws Exception {
        start(PasswordAuthenticator.disabled(), "127.0.0.1");
        for (String path : new String[] {"/assets/..%2F..%2Fpom.xml", "/.env", "/assets/x.exe", "/WEB-INF/x.class"}) {
            assertEquals(404, get(path, null).statusCode(), path);
        }
    }

    @Test
    void runsCommandsAndReturnsRespBytes() throws Exception {
        start(PasswordAuthenticator.disabled(), "127.0.0.1");
        HttpResponse<String> res = post("/api/command", "SET greeting \"hello world\"", null, true);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"type\":\"simple\""));
        assertTrue(res.body().contains("$11\\r\\nhello world"));
    }

    @Test
    void rejectsPostWithoutCsrfHeader() throws Exception {
        start(PasswordAuthenticator.disabled(), "127.0.0.1");
        assertEquals(403, post("/api/command", "FLUSHALL", null, false).statusCode());
    }

    @Test
    void rejectsForeignHostHeaderToBlockDnsRebinding() throws Exception {
        start(PasswordAuthenticator.disabled(), "127.0.0.1");
        // java.net.http won't let us forge Host, so speak raw HTTP.
        try (Socket socket = new Socket("127.0.0.1", dashboard.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /api/stats HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            assertTrue(status.contains(" 403 "), status);
        }
    }

    @Test
    void passwordModeRequiresLoginAndIssuesWorkingToken() throws Exception {
        start(PasswordAuthenticator.withPassword(PASSWORD), "127.0.0.1");

        assertEquals(401, get("/api/stats", null).statusCode());
        assertEquals(401, get("/api/stats", "made-up-token").statusCode());
        assertTrue(get("/api/session", null).body().contains("\"authenticated\":false"));
        assertEquals(401, post("/api/login", "wrong", null, true).statusCode());

        HttpResponse<String> login = post("/api/login", PASSWORD, null, true);
        assertEquals(200, login.statusCode());
        Matcher m = Pattern.compile("\"token\":\"([A-Za-z0-9_-]+)\"").matcher(login.body());
        assertTrue(m.find());
        String token = m.group(1);
        assertTrue(token.length() >= 40, "token should carry 256 bits of randomness");

        assertEquals(200, get("/api/stats", token).statusCode());
        assertEquals(200, post("/api/logout", "", token, true).statusCode());
        assertEquals(401, get("/api/stats", token).statusCode());
    }

    @Test
    void refusesToExposeDashboardOnNetworkWithoutPassword() {
        assertThrows(IllegalStateException.class, () -> start(PasswordAuthenticator.disabled(), "0.0.0.0"));
    }
}
