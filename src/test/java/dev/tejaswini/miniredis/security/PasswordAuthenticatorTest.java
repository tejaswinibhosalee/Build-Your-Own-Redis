package dev.tejaswini.miniredis.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordAuthenticatorTest {

    @Test
    void acceptsOnlyTheExactPassword() {
        PasswordAuthenticator auth = PasswordAuthenticator.withPassword("correct horse battery staple");
        assertTrue(auth.isEnabled());
        assertTrue(auth.check("correct horse battery staple"));
        assertFalse(auth.check("correct horse battery stapl"));
        assertFalse(auth.check("correct horse battery staple "));
        assertFalse(auth.check(""));
        assertFalse(auth.check(null));
    }

    @Test
    void disabledAuthenticatorNeverAcceptsAnything() {
        PasswordAuthenticator auth = PasswordAuthenticator.disabled();
        assertFalse(auth.isEnabled());
        assertFalse(auth.check("anything"));
    }

    @Test
    void rejectsEmptyPassword() {
        assertThrows(IllegalArgumentException.class, () -> PasswordAuthenticator.withPassword(""));
    }

    @Test
    void refusesThePlaceholderFromEnvExample() {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordAuthenticator.withPassword(PasswordAuthenticator.EXAMPLE_PLACEHOLDER));
    }

    @Test
    void readsPasswordFileAndIgnoresTrailingNewline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("password.txt");
        Files.writeString(file, "from-a-file-123\n");
        PasswordAuthenticator auth = PasswordAuthenticator.fromEnvironmentOrFile(Optional.of(file));
        assertTrue(auth.check("from-a-file-123"));
    }

    @Test
    void toStringNeverRevealsTheSecret() {
        String text = PasswordAuthenticator.withPassword("super-secret-value").toString();
        assertFalse(text.contains("super-secret-value"));
    }
}
