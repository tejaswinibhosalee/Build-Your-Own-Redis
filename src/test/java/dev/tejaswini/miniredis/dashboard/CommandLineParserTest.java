package dev.tejaswini.miniredis.dashboard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandLineParserTest {

    @Test
    void splitsOnWhitespaceAndHonoursQuotes() {
        assertEquals(List.of("SET", "greeting", "hello world"), CommandLineParser.parse("SET greeting \"hello world\""));
        assertEquals(List.of("SET", "k", "it's"), CommandLineParser.parse("SET k 'it\\'s'"));
        assertEquals(List.of("SET", "k", "a\"b\\c\n"), CommandLineParser.parse("SET k \"a\\\"b\\\\c\\n\""));
        assertEquals(List.of("SET", "k", ""), CommandLineParser.parse("  SET   k  \"\"  "));
    }

    @Test
    void rejectsUnbalancedQuotes() {
        assertThrows(IllegalArgumentException.class, () -> CommandLineParser.parse("SET k \"oops"));
    }
}
