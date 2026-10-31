package dev.tejaswini.miniredis.core;

/**
 * Matches Redis-style glob patterns ({@code *} = any run of characters,
 * {@code ?} = exactly one character) against a key.
 *
 * <p><b>Why not just convert the glob to a regex?</b> Java's regex engine
 * backtracks, so a hostile pattern such as {@code *a*a*a*a*a*a*b} can take
 * exponential time on a long key - a "ReDoS" (regular-expression denial of
 * service). Since KEYS runs while holding the global command lock, one bad
 * pattern would freeze every client. This matcher is the classic greedy
 * two-pointer wildcard algorithm: it remembers only the <i>last</i>
 * {@code *} it saw and retries from there, which bounds the worst case to
 * {@code O(pattern.length * text.length)} with no recursion and no extra
 * memory.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static boolean matches(String pattern, String text) {
        int p = 0;          // position in pattern
        int t = 0;          // position in text
        int starP = -1;     // pattern index just after the most recent '*'
        int starT = -1;     // text index that '*' is currently "absorbing" up to

        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                // Exact (or single-wildcard) match: advance both.
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                // Remember this star; first try letting it match nothing.
                starP = ++p;
                starT = t;
            } else if (starP != -1) {
                // Mismatch, but an earlier '*' can swallow one more character.
                p = starP;
                t = ++starT;
            } else {
                return false;
            }
        }
        // Text consumed: any remaining pattern must be all '*'.
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
