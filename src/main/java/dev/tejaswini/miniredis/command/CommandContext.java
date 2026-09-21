package dev.tejaswini.miniredis.command;

import dev.tejaswini.miniredis.core.Database;
import dev.tejaswini.miniredis.persistence.AofWriter;

/**
 * Everything a command implementation needs besides its own arguments.
 * Bundling it into one record (rather than handing out the raw
 * {@link Database} only) leaves room to grow - e.g. a future command that
 * needs to know which client is asking - without changing every existing
 * command's method signature.
 *
 * @param db  the keyspace to read/mutate
 * @param aof the append-only log to persist writes to, or {@code null} if
 *            persistence is disabled for this server instance
 */
public record CommandContext(Database db, AofWriter aof) {
}
