package dev.tejaswini.miniredis.dashboard;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void writesNestedStructures() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("n", 1);
        map.put("ok", true);
        map.put("list", List.of("a", 2));
        map.put("none", null);
        assertEquals("{\"n\":1,\"ok\":true,\"list\":[\"a\",2],\"none\":null}", Json.write(map));
    }

    @Test
    void escapesCharactersThatCouldBreakOutOfAString() {
        assertEquals("\"\\\"\\\\\\n\\u003cscript\\u003e\\u0001\"", Json.write("\"\\\n<script>\u0001"));
    }
}
