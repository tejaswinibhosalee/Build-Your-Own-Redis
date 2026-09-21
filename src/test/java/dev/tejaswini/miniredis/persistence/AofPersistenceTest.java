package dev.tejaswini.miniredis.persistence;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.command.CommandRegistry;
import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AofPersistenceTest {

    @Test
    void restartReplaysWritesFromTheAofFile(@TempDir Path dir) throws IOException {
        Path aofPath = dir.resolve("test.aof");

        // "First run": issue a handful of writes, then shut down.
        try (AofWriter aof = new AofWriter(aofPath)) {
            CommandExecutor executor = new CommandExecutor(new Database(), aof);
            CommandRegistry.registerAll(executor);
            executor.execute(List.of("SET", "greeting", "hello"));
            executor.execute(List.of("RPUSH", "list", "a", "b", "c"));
            executor.execute(List.of("HSET", "hash", "field", "value"));
            executor.execute(List.of("SADD", "set", "x", "y"));
            executor.execute(List.of("GET", "greeting")); // reads must not appear on replay
        }

        // "Second run": fresh Database, replay the same file into it.
        Database restored = new Database();
        try (AofWriter aof = new AofWriter(aofPath)) {
            CommandExecutor executor = new CommandExecutor(restored, aof);
            CommandRegistry.registerAll(executor);
            AofLoader.load(aofPath, executor);

            assertEquals("hello", restored.getString("greeting"));
            assertEquals(List.of("a", "b", "c"), List.copyOf(restored.getList("list")));
            assertEquals("value", restored.getHash("hash").get("field"));
            assertEquals(java.util.Set.of("x", "y"), restored.getSet("set"));
        }
    }

    @Test
    void saveRewritesToACompactRepresentationThatStillReplaysCorrectly(@TempDir Path dir) throws IOException {
        Path aofPath = dir.resolve("test.aof");

        try (AofWriter aof = new AofWriter(aofPath)) {
            CommandExecutor executor = new CommandExecutor(new Database(), aof);
            CommandRegistry.registerAll(executor);

            // Many increments -> AOF has many entries for the same key.
            for (int i = 0; i < 20; i++) {
                executor.execute(List.of("INCR", "counter"));
            }
            long sizeBeforeRewrite = java.nio.file.Files.size(aofPath);

            Reply saveReply = executor.execute(List.of("SAVE"));
            assertEquals(Reply.ok(), saveReply);

            long sizeAfterRewrite = java.nio.file.Files.size(aofPath);
            org.junit.jupiter.api.Assertions.assertTrue(sizeAfterRewrite < sizeBeforeRewrite,
                    "rewrite should compact 20 INCRs down to one SET");
        }

        Database restored = new Database();
        try (AofWriter aof = new AofWriter(aofPath)) {
            CommandExecutor executor = new CommandExecutor(restored, aof);
            CommandRegistry.registerAll(executor);
            AofLoader.load(aofPath, executor);
            assertEquals("20", restored.getString("counter"));
        }
    }

    @Test
    void loadingAMissingAofFileIsANoOp(@TempDir Path dir) {
        Database db = new Database();
        CommandExecutor executor = new CommandExecutor(db, null);
        CommandRegistry.registerAll(executor);
        AofLoader.load(dir.resolve("does-not-exist.aof"), executor); // should not throw
        assertEquals(0, db.keys().size());
    }
}
