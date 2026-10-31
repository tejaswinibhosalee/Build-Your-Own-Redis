package dev.tejaswini.miniredis.command;

/** Where a command came from. Used for AOF logging decisions and for the dashboard's activity feed. */
public enum CommandOrigin {
    /** A real client over TCP (redis-cli, Jedis, telnet, ...). */
    NETWORK,
    /** Typed into the web dashboard's console. */
    DASHBOARD,
    /**
     * Re-executed from the AOF file at startup. These are never written back
     * to the AOF (that would duplicate the log on every restart) and are not
     * counted as live traffic.
     */
    AOF_REPLAY
}
