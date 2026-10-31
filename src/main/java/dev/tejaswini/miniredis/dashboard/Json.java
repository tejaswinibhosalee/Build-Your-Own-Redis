package dev.tejaswini.miniredis.dashboard;

import java.util.Collection;
import java.util.Map;

/**
 * A tiny JSON <i>writer</i> - just enough for the dashboard API, so the
 * project keeps zero runtime dependencies.
 *
 * <p>Supported values: {@code null}, {@link Boolean}, {@link Number},
 * {@link CharSequence}, {@code long[]}, {@link Map} (keys via
 * {@code toString()}), {@link Collection} and {@link Enum}.
 *
 * <p><b>Escaping is the security-critical part.</b> Keys and values come
 * from clients, so a value like {@code "};alert(1)//} must stay a string.
 * Besides the characters JSON requires escaping ({@code "} {@code \} and
 * control characters), we also escape {@code <}, {@code >}, {@code &} and
 * the line separators U+2028/U+2029, so the output is safe even if it is
 * ever embedded inside an HTML page.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Double d when d.isNaN() || d.isInfinite() -> sb.append("null");
            case Float f when f.isNaN() || f.isInfinite() -> sb.append("null");
            case Number n -> sb.append(n);
            case CharSequence s -> appendString(sb, s);
            case Enum<?> e -> appendString(sb, e.name());
            case long[] array -> {
                sb.append('[');
                for (int i = 0; i < array.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(array[i]);
                }
                sb.append(']');
            }
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    appendString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(',');
                    first = false;
                    append(sb, item);
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported JSON type: " + value.getClass());
        }
    }

    private static void appendString(StringBuilder sb, CharSequence s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<', '>', '&', ' ', ' ' -> sb.append(String.format("\\u%04x", (int) c));
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
