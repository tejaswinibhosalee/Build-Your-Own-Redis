package dev.tejaswini.miniredis.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.security.LoginThrottle;
import dev.tejaswini.miniredis.security.PasswordAuthenticator;
import dev.tejaswini.miniredis.stats.ServerStats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The web dashboard: a small HTTP server (the JDK's built-in
 * {@link HttpServer}, no frameworks) that serves a single-page UI plus a
 * JSON API for it.
 *
 * <h2>Request pipeline</h2>
 * Every request goes through {@link #handle} - there is exactly one entry
 * point, so no endpoint can forget a security check:
 * <ol>
 *   <li>Security headers are added to every response (CSP, nosniff,
 *       frame denial, no referrer, no caching).</li>
 *   <li><b>Host check</b> (when bound to loopback): blocks DNS-rebinding
 *       attacks, where a malicious site re-points its own domain name at
 *       127.0.0.1 to talk to services on your machine.</li>
 *   <li>For POSTs: a custom {@code X-Requested-With} header is required and
 *       the {@code Origin} (if any) must match - this blocks CSRF, because a
 *       different website cannot add custom headers to a cross-origin
 *       request without a CORS approval we never give.</li>
 *   <li>For API calls when a password is set: a valid session token in the
 *       {@code Authorization} header.</li>
 *   <li>Request bodies are capped at {@value #MAX_BODY_BYTES} bytes.</li>
 *   <li>Unexpected exceptions return a generic 500 - stack traces are
 *       logged server-side, never sent to the browser.</li>
 * </ol>
 */
public final class DashboardServer {

    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_HEADER_VALUE = "mini-redis-dashboard";

    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'none'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "font-src 'self'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'");

    /** Static files served from the classpath. An allowlist: nothing else on disk is reachable. */
    private static final Map<String, StaticFile> STATIC_FILES = Map.of(
            "/", new StaticFile("index.html", "text/html; charset=utf-8"),
            "/index.html", new StaticFile("index.html", "text/html; charset=utf-8"),
            "/assets/app.js", new StaticFile("app.js", "text/javascript; charset=utf-8"),
            "/assets/styles.css", new StaticFile("styles.css", "text/css; charset=utf-8"),
            "/assets/favicon.svg", new StaticFile("favicon.svg", "image/svg+xml"));

    private record StaticFile(String resource, String contentType) {
    }

    /** Thrown inside handlers to short-circuit with an HTTP error status. */
    static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final PasswordAuthenticator authenticator;
    private final SessionManager sessions = new SessionManager();
    private final LoginThrottle loginThrottle = new LoginThrottle();
    private final DashboardApi api;
    private final Map<String, byte[]> staticContent = new HashMap<>();
    private HttpServer http;

    public DashboardServer(String bindAddress, int port, CommandExecutor executor, ServerStats stats,
                           PasswordAuthenticator authenticator, DashboardApi.ServerInfo info) throws IOException {
        this.bindAddress = InetAddress.getByName(bindAddress);
        this.requestedPort = port;
        this.authenticator = authenticator;
        this.api = new DashboardApi(executor, stats, info, authenticator.isEnabled());

        if (!this.bindAddress.isLoopbackAddress() && !authenticator.isEnabled()) {
            // Secure by default: an admin UI that can run FLUSHALL must never
            // be reachable from the network without a password.
            throw new IllegalStateException("Refusing to expose the dashboard on " + bindAddress
                    + " without a password. Set " + PasswordAuthenticator.PASSWORD_ENV_VAR
                    + " or bind the dashboard to 127.0.0.1.");
        }
        for (StaticFile file : STATIC_FILES.values()) {
            staticContent.computeIfAbsent(file.resource(), DashboardServer::loadResource);
        }
    }

    private static byte[] loadResource(String name) {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing dashboard resource: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 0);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        System.out.println("Dashboard available at http://"
                + (bindAddress.isLoopbackAddress() ? "localhost" : bindAddress.getHostAddress())
                + ":" + getPort() + "/");
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    public int getPort() {
        return http != null ? http.getAddress().getPort() : requestedPort;
    }

    // ------------------------------------------------------------------
    // Request pipeline
    // ------------------------------------------------------------------

    private void handle(HttpExchange ex) {
        try (ex) {
            addSecurityHeaders(ex);
            try {
                route(ex);
            } catch (HttpError e) {
                sendJson(ex, e.status, Map.of("error", e.getMessage()));
            } catch (RuntimeException e) {
                System.err.println("Dashboard error on " + ex.getRequestMethod() + " "
                        + ex.getRequestURI().getPath() + ": " + e);
                sendJson(ex, 500, Map.of("error", "Internal server error"));
            }
        } catch (IOException e) {
            // Browser went away mid-response; nothing useful to do.
        }
    }

    private void route(HttpExchange ex) throws IOException {
        checkHost(ex);
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = ex.getRequestURI().getPath();

        if (!path.startsWith("/api/")) {
            serveStatic(ex, method, path);
            return;
        }

        if (method.equals("POST")) {
            checkCsrf(ex);
        } else if (!method.equals("GET")) {
            throw new HttpError(405, "Method not allowed");
        }

        String route = method + " " + path;
        // Endpoints reachable without a session: the login flow itself.
        switch (route) {
            case "GET /api/session" -> {
                sendJson(ex, 200, api.session(isAuthenticated(ex)));
                return;
            }
            case "POST /api/login" -> {
                handleLogin(ex);
                return;
            }
            default -> {
                // fall through to authenticated routes
            }
        }

        if (!isAuthenticated(ex)) {
            throw new HttpError(401, "Authentication required");
        }
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Object body = switch (route) {
            case "POST /api/logout" -> {
                sessions.invalidate(bearerToken(ex));
                yield Map.of("ok", true);
            }
            case "GET /api/stats" -> api.stats();
            case "GET /api/keys" -> api.keys(query.getOrDefault("pattern", "*"), query.getOrDefault("type", "all"));
            case "GET /api/key" -> api.key(required(query, "name"));
            case "GET /api/activity" -> api.activity(parseLong(query.getOrDefault("after", "0")));
            case "POST /api/command" -> api.command(readBody(ex));
            case "POST /api/demo-data" -> api.loadDemoData();
            default -> throw new HttpError(404, "Not found");
        };
        sendJson(ex, 200, body);
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!authenticator.isEnabled()) {
            throw new HttpError(400, "Authentication is not enabled on this server");
        }
        String password = readBody(ex);
        if (authenticator.check(password)) {
            loginThrottle.onSuccess();
            sendJson(ex, 200, Map.of("token", sessions.create()));
        } else {
            loginThrottle.onFailure(); // deliberate delay to slow down guessing
            throw new HttpError(401, "Invalid password");
        }
    }

    private boolean isAuthenticated(HttpExchange ex) {
        return !authenticator.isEnabled() || sessions.validate(bearerToken(ex));
    }

    private static String bearerToken(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }

    // ------------------------------------------------------------------
    // Security checks
    // ------------------------------------------------------------------

    private static void addSecurityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Cache-Control", "no-store");
    }

    /**
     * DNS-rebinding protection. A loopback-bound dashboard should only ever be
     * addressed as localhost / 127.0.0.1 / [::1]; any other Host means the
     * request was routed here under someone else's domain name.
     */
    private void checkHost(HttpExchange ex) {
        if (!bindAddress.isLoopbackAddress()) {
            return; // network-exposed mode always requires a password instead
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) {
            throw new HttpError(400, "Missing Host header");
        }
        String hostname = stripPort(host.toLowerCase(Locale.ROOT));
        if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(hostname)) {
            throw new HttpError(403, "Invalid Host header");
        }
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) { // IPv6 literal, e.g. [::1]:8080
            int end = host.indexOf(']');
            return end > 0 ? host.substring(0, end + 1) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    /** CSRF protection for state-changing requests. */
    private static void checkCsrf(HttpExchange ex) {
        var headers = ex.getRequestHeaders();
        if (!CSRF_HEADER_VALUE.equals(headers.getFirst(CSRF_HEADER))) {
            throw new HttpError(403, "Missing " + CSRF_HEADER + " header");
        }
        String origin = headers.getFirst("Origin");
        String host = headers.getFirst("Host");
        if (origin != null && host != null
                && !origin.equalsIgnoreCase("http://" + host) && !origin.equalsIgnoreCase("https://" + host)) {
            throw new HttpError(403, "Cross-origin request rejected");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void serveStatic(HttpExchange ex, String method, String path) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) {
            throw new HttpError(405, "Method not allowed");
        }
        StaticFile file = STATIC_FILES.get(path);
        if (file == null) {
            throw new HttpError(404, "Not found");
        }
        byte[] content = staticContent.get(file.resource());
        ex.getResponseHeaders().set("Content-Type", file.contentType());
        if (method.equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(content);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new HttpError(413, "Request body too large (max " + MAX_BODY_BYTES + " bytes)");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new HttpError(400, "Malformed query string");
            }
        }
        return result;
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null) {
            throw new HttpError(400, "Missing query parameter: " + name);
        }
        return value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "Expected a number");
        }
    }
}
