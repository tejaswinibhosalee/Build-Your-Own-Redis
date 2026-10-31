package dev.tejaswini.miniredis.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Holds the server password (if one is configured) and checks login attempts
 * against it.
 *
 * <h2>Security decisions, and why</h2>
 * <ul>
 *   <li><b>No password on the command line.</b> Process arguments are visible
 *       to every user on the machine via {@code ps}. The password is read from
 *       the {@code MINI_REDIS_PASSWORD} environment variable or from a file.</li>
 *   <li><b>Only a hash is kept in memory.</b> We store SHA-256(password), never
 *       the plain text, so it can't accidentally end up in a log line or a
 *       {@code toString()}.</li>
 *   <li><b>Constant-time comparison.</b> {@code String.equals} returns as soon
 *       as one character differs, so the time it takes leaks how many leading
 *       characters were right (a "timing attack"). Hashing both sides to a
 *       fixed 32 bytes and comparing with {@link MessageDigest#isEqual} (which
 *       always inspects every byte) removes both the length and the prefix
 *       leak.</li>
 * </ul>
 */
public final class PasswordAuthenticator {

    /** Environment variable the password is read from. */
    public static final String PASSWORD_ENV_VAR = "MINI_REDIS_PASSWORD";

    /** Passwords shorter than this still work, but trigger a startup warning. */
    public static final int RECOMMENDED_MIN_LENGTH = 12;

    private final byte[] passwordHash; // null => authentication disabled

    private PasswordAuthenticator(byte[] passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** No password: every client is trusted (only safe on a loopback-only server). */
    public static PasswordAuthenticator disabled() {
        return new PasswordAuthenticator(null);
    }

    public static PasswordAuthenticator withPassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
        return new PasswordAuthenticator(sha256(password));
    }

    /**
     * Resolves the password from (in priority order) a password file, then the
     * {@value #PASSWORD_ENV_VAR} environment variable. Returns a disabled
     * authenticator if neither is set.
     */
    public static PasswordAuthenticator fromEnvironmentOrFile(Optional<Path> passwordFile) throws IOException {
        if (passwordFile.isPresent()) {
            // strip() removes the trailing newline most editors add to files.
            String fromFile = Files.readString(passwordFile.get(), StandardCharsets.UTF_8).strip();
            if (fromFile.isEmpty()) {
                throw new IllegalArgumentException("password file is empty: " + passwordFile.get());
            }
            return withPassword(fromFile);
        }
        String fromEnv = System.getenv(PASSWORD_ENV_VAR);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return withPassword(fromEnv);
        }
        return disabled();
    }

    public boolean isEnabled() {
        return passwordHash != null;
    }

    /** Constant-time check of a login attempt. Always false when auth is disabled. */
    public boolean check(String attempt) {
        if (passwordHash == null || attempt == null) {
            return false;
        }
        return MessageDigest.isEqual(passwordHash, sha256(attempt));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to exist on every JVM", e);
        }
    }

    @Override
    public String toString() {
        // Never print anything derived from the secret.
        return "PasswordAuthenticator[enabled=" + isEnabled() + "]";
    }
}
