package br.unb.cic.td20.avoidance;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared operational progress for emission, inverse reconstruction and persistence. */
public final class ReconstructionProgress implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReconstructionProgress.class);
    private final String phase;
    private final LongSupplier completed;
    private final Supplier<String> details;
    private final java.util.concurrent.ScheduledExecutorService reporter;
    private volatile long total;
    private long previousCount, previousNanos = System.nanoTime();

    public ReconstructionProgress(String phase, long total, LongSupplier completed) {
        this(phase, total, completed, () -> "");
    }

    public ReconstructionProgress(String phase, long total, LongSupplier completed, Supplier<String> details) {
        if (total < -1) throw new IllegalArgumentException("A phase total is nonnegative or unknown (-1)");
        this.phase = phase; this.total = total; this.completed = completed; this.details = details;
        previousCount = completed.getAsLong();
        reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("phase-progress").factory());
        report();
        reporter.scheduleWithFixedDelay(this::report, 10, 10, TimeUnit.SECONDS);
    }

    /** Called only after the exhaustive count and iterator status check succeed. */
    public void countedTotal(long count) {
        if (count < 0) throw new IllegalArgumentException("Nonnegative inventory count required");
        total = count;
    }

    private synchronized void report() {
        long count = completed.getAsLong(), now = System.nanoTime(), denominator = total;
        double seconds = Math.max(1, now - previousNanos) / 1_000_000_000.0;
        String rate = String.format(Locale.ROOT, "%.3f", (count - previousCount) / seconds);
        String inventory = denominator < 0
                ? "processed=" + count + ", total=unknown (exhaustive count in progress)"
                : denominator == 0
                ? "processed=" + count + "/0, remaining=" + (denominator - count)
                : "processed=" + count + "/" + denominator + " ("
                    + String.format(Locale.ROOT, "%.2f", 100.0 * count / denominator)
                    + "%), remaining=" + (denominator - count);
        String extra = details.get();
        log.info("{}: {}, throughput={}/s{}", phase, inventory, rate,
                extra.isEmpty() ? "" : ", " + extra);
        previousCount = count; previousNanos = now;
    }

    @Override public void close() { reporter.shutdownNow(); report(); }
}
