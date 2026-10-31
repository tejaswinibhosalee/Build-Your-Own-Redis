package dev.tejaswini.miniredis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

    @Test
    void matchesStarAndQuestionMark() {
        assertTrue(GlobMatcher.matches("*", "anything"));
        assertTrue(GlobMatcher.matches("*", ""));
        assertTrue(GlobMatcher.matches("user:*", "user:1001"));
        assertTrue(GlobMatcher.matches("user:?00?", "user:1001"));
        assertTrue(GlobMatcher.matches("*:*:end", "a:b:c:end"));
        assertFalse(GlobMatcher.matches("user:*", "session:1"));
        assertFalse(GlobMatcher.matches("user:?", "user:10"));
        assertFalse(GlobMatcher.matches("", "x"));
    }

    @Test
    void treatsRegexMetacharactersLiterally() {
        assertTrue(GlobMatcher.matches("a.b", "a.b"));
        assertFalse(GlobMatcher.matches("a.b", "axb"));
        assertTrue(GlobMatcher.matches("(x)+[y]", "(x)+[y]"));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void pathologicalPatternFinishesQuickly() {
        // With a backtracking regex this pattern takes exponential time (ReDoS).
        String key = "a".repeat(5_000);
        assertFalse(GlobMatcher.matches("*a*a*a*a*a*a*a*a*a*a*a*a*b", key));
    }
}
