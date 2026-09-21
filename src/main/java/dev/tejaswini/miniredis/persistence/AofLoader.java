package dev.tejaswini.miniredis.persistence;

import dev.tejaswini.miniredis.command.CommandExecutor;
import dev.tejaswini.miniredis.protocol.RespReader;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Startup-time replay of an AOF file: read it back with the exact same
 * {@link RespReader} that parses live client traffic, and feed each parsed
 * command straight into the {@link CommandExecutor} as if a client had
 * typed it. Reusing the protocol parser here (instead of inventing a
 * separate "save file format") is the main trick that makes AOF simple to
 * implement correctly.
 */
public final class AofLoader {

    private AofLoader() {
    }

    public static void load(Path path, CommandExecutor executor) {
        if (!Files.exists(path)) {
            return;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            RespReader reader = new RespReader(in);
            List<String> args;
            while ((args = reader.readCommand()) != null) {
                if (args.isEmpty()) {
                    continue;
                }
                // logToAof=false: these commands are already in the file we're
                // reading - re-appending them here would duplicate every entry
                // on every restart.
                executor.execute(args, false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load AOF file: " + path, e);
        }
    }
}
