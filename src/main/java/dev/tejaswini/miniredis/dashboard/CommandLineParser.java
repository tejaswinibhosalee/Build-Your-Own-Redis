package dev.tejaswini.miniredis.dashboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a console line into arguments the way {@code redis-cli} does, so
 * values with spaces work in the dashboard:
 *
 * <pre>
 *   SET greeting "hello world"      -&gt; [SET, greeting, hello world]
 *   SET quote 'it\'s'               -&gt; [SET, quote, it's]
 *   SET path "C:\\temp\n"           -&gt; backslash escapes inside "double quotes"
 * </pre>
 *
 * <p>Double-quoted strings support {@code \" \\ \n \r \t}. Single-quoted
 * strings are taken literally except for {@code \'}.
 */
final class CommandLineParser {

    private CommandLineParser() {
    }

    static List<String> parse(String line) {
        List<String> args = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            StringBuilder current = new StringBuilder();
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                boolean closed = false;
                while (i < n) {
                    char ch = line.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char next = line.charAt(i + 1);
                        if (quote == '"') {
                            switch (next) {
                                case 'n' -> current.append('\n');
                                case 'r' -> current.append('\r');
                                case 't' -> current.append('\t');
                                default -> current.append(next); // \" and \\ (and any other char literally)
                            }
                            i += 2;
                            continue;
                        } else if (next == '\'') {
                            current.append('\'');
                            i += 2;
                            continue;
                        }
                    }
                    if (ch == quote) {
                        closed = true;
                        i++;
                        break;
                    }
                    current.append(ch);
                    i++;
                }
                if (!closed) {
                    throw new IllegalArgumentException("Invalid argument(s): unbalanced quotes");
                }
                if (i < n && !Character.isWhitespace(line.charAt(i))) {
                    throw new IllegalArgumentException("Invalid argument(s): closing quote must be followed by a space");
                }
            } else {
                while (i < n && !Character.isWhitespace(line.charAt(i))) {
                    current.append(line.charAt(i));
                    i++;
                }
            }
            args.add(current.toString());
        }
        return args;
    }
}
