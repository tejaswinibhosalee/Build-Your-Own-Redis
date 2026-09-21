package dev.tejaswini.miniredis.persistence;

import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.protocol.Reply;
import dev.tejaswini.miniredis.protocol.RespWriter;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durability via an Append-Only File (AOF): every write command is
 * re-encoded as RESP and appended to a plain file. On restart, replaying
 * that file from the top through the same command dispatcher rebuilds the
 * exact same dataset - persistence "for free", reusing the same protocol
 * code that parses client requests.
 *
 * <p>This mirrors Redis's real {@code appendonly.aof} mechanism, simplified
 * to one thing: we {@code flush()} (and rely on the OS to eventually
 * persist to disk) after every single write, which is the safest but
 * slowest of Redis's three {@code appendfsync} policies ({@code always} /
 * {@code everysec} / {@code no}) - a deliberate "correct first, fast
 * later" tradeoff for a learning project.
 *
 * <p>{@link #rewrite} implements the other half of the real AOF story:
 * <b>compaction</b>. A log that records "INCR counter" a million times is
 * wasteful; a rewrite replaces the whole file with the minimal set of
 * commands needed to reconstruct the *current* dataset in one shot (here,
 * triggered manually via the {@code SAVE} command instead of automatically
 * like Redis's {@code BGREWRITEAOF}).
 */
public final class AofWriter implements Closeable {

    private final Path path;
    private OutputStream out;

    public AofWriter(Path path) throws IOException {
        this.path = path;
        this.out = new BufferedOutputStream(new FileOutputStream(path.toFile(), true));
    }

    public synchronized void append(List<String> args) {
        try {
            writeCommand(out, args);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append to AOF", e);
        }
    }

    public synchronized void rewrite(Database db) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".rewrite.tmp");
            try (OutputStream tmpOut = new BufferedOutputStream(new FileOutputStream(tmp.toFile()))) {
                for (String key : db.keys()) {
                    writeReconstructionCommands(tmpOut, db, key);
                }
            }
            out.close();
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            out = new BufferedOutputStream(new FileOutputStream(path.toFile(), true));
        } catch (IOException e) {
            throw new UncheckedIOException("AOF rewrite failed", e);
        }
    }

    private void writeReconstructionCommands(OutputStream sink, Database db, String key) throws IOException {
        switch (db.type(key)) {
            case "string" -> writeCommand(sink, List.of("SET", key, db.getString(key)));
            case "list" -> {
                Deque<String> list = db.getList(key);
                if (list == null || list.isEmpty()) return;
                List<String> cmd = new ArrayList<>(List.of("RPUSH", key));
                cmd.addAll(list);
                writeCommand(sink, cmd);
            }
            case "hash" -> {
                Map<String, String> hash = db.getHash(key);
                if (hash == null || hash.isEmpty()) return;
                List<String> cmd = new ArrayList<>(List.of("HSET", key));
                hash.forEach((field, value) -> {
                    cmd.add(field);
                    cmd.add(value);
                });
                writeCommand(sink, cmd);
            }
            case "set" -> {
                Set<String> set = db.getSet(key);
                if (set == null || set.isEmpty()) return;
                List<String> cmd = new ArrayList<>(List.of("SADD", key));
                cmd.addAll(set);
                writeCommand(sink, cmd);
            }
            default -> {
                return;
            }
        }
        long ttlMillis = db.ttlMillis(key);
        if (ttlMillis >= 0) {
            long seconds = Math.max(1, (ttlMillis + 999) / 1000);
            writeCommand(sink, List.of("EXPIRE", key, Long.toString(seconds)));
        }
    }

    private static void writeCommand(OutputStream sink, List<String> args) throws IOException {
        Reply.Array asArray = new Reply.Array(args.stream().<Reply>map(Reply.BulkString::new).toList());
        RespWriter.write(sink, asArray);
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
