package dev.tejaswini.miniredis.command;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code Database.activeExpireCycle()} on a fixed interval in the
 * background, so keys with a TTL get cleaned up even if no client ever
 * reads them again after they expire. Each sweep runs through
 * {@link CommandExecutor#runExclusive}, taking the same lock a normal
 * command would, so it can never observe or corrupt state mid-command.
 */
public final class ExpiryScheduler {

    private static final long INTERVAL_MILLIS = 100;

    private final CommandExecutor executor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "expiry-cycle");
        thread.setDaemon(true);
        return thread;
    });

    public ExpiryScheduler(CommandExecutor executor) {
        this.executor = executor;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
                () -> executor.runExclusive(() -> executor.database().activeExpireCycle()),
                INTERVAL_MILLIS, INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
