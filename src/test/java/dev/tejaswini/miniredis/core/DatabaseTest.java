package dev.tejaswini.miniredis.core;

import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void getStringReturnsNullForMissingKey() {
        Database db = new Database();
        assertNull(db.getString("missing"));
    }

    @Test
    void setStringThenGetStringRoundTrips() {
        Database db = new Database();
        db.setString("greeting", "hello");
        assertEquals("hello", db.getString("greeting"));
    }

    @Test
    void wrongTypeAccessThrows() {
        Database db = new Database();
        db.setString("k", "v");
        assertThrows(WrongTypeException.class, () -> db.getOrCreateList("k"));
        assertThrows(WrongTypeException.class, () -> db.getOrCreateHash("k"));
        assertThrows(WrongTypeException.class, () -> db.getOrCreateSet("k"));
    }

    @Test
    void expiredKeyIsInvisibleAfterItsTtlPasses() throws InterruptedException {
        Database db = new Database();
        db.setString("temp", "v");
        db.setExpireAt("temp", System.currentTimeMillis() + 20);
        assertTrue(db.exists("temp"));
        Thread.sleep(40);
        assertFalse(db.exists("temp")); // lazy expiry kicks in on access
        assertNull(db.getString("temp"));
    }

    @Test
    void plainSetClearsAnyPreviousTtl() {
        Database db = new Database();
        db.setString("k", "v1");
        db.setExpireAt("k", System.currentTimeMillis() + 100_000);
        db.setString("k", "v2"); // real Redis: a fresh SET removes the TTL
        assertEquals(-1, db.ttlMillis("k"));
    }

    @Test
    void ttlMillisReportsMinusTwoForMissingKeyAndMinusOneForNoExpiry() {
        Database db = new Database();
        assertEquals(-2, db.ttlMillis("missing"));
        db.setString("persistent", "v");
        assertEquals(-1, db.ttlMillis("persistent"));
    }

    @Test
    void activeExpireCycleSweepsPastKeysWithoutAnyoneAccessingThem() throws InterruptedException {
        Database db = new Database();
        db.setString("temp", "v");
        db.setExpireAt("temp", System.currentTimeMillis() + 10);
        Thread.sleep(30);
        db.activeExpireCycle();
        // internal removal happened without ever calling exists()/get() on "temp"
        assertEquals(Set.of(), db.keys());
    }

    @Test
    void listOperationsPreserveInsertionOrder() {
        Database db = new Database();
        Deque<String> list = db.getOrCreateList("mylist");
        list.addLast("a");
        list.addLast("b");
        list.addFirst("z");
        assertEquals(java.util.List.of("z", "a", "b"), java.util.List.copyOf(db.getList("mylist")));
    }

    @Test
    void deleteIfEmptyRemovesContainerKeysOnceEmptiedButNotWhileOccupied() {
        Database db = new Database();
        Deque<String> list = db.getOrCreateList("mylist");
        list.addLast("only");
        db.deleteIfEmpty("mylist");
        assertTrue(db.exists("mylist"));

        list.removeFirst();
        db.deleteIfEmpty("mylist");
        assertFalse(db.exists("mylist"));
    }

    @Test
    void keysMatchingSupportsGlobStar() {
        Database db = new Database();
        db.setString("user:1", "a");
        db.setString("user:2", "b");
        db.setString("order:1", "c");
        assertEquals(Set.of("user:1", "user:2"), db.keysMatching("user:*"));
    }

    @Test
    void typeReflectsTheStoredValueKind() {
        Database db = new Database();
        assertEquals("none", db.type("missing"));
        db.setString("s", "v");
        assertEquals("string", db.type("s"));
        db.getOrCreateList("l");
        assertEquals("list", db.type("l"));
        db.getOrCreateHash("h");
        assertEquals("hash", db.type("h"));
        db.getOrCreateSet("set");
        assertEquals("set", db.type("set"));
    }
}
