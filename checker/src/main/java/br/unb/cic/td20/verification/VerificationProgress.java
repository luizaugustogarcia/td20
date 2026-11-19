package br.unb.cic.td20.verification;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Time-based reporting, including phases that do not query certificates. */
public final class VerificationProgress implements AutoCloseable {
    private final ScheduledExecutorService reporter;
    private final long started = System.nanoTime();
    private volatile Supplier<String> status = () -> "starting";
    private LongSupplier completed;
    private String unit;
    private long previousCount, previousNanos;

    public VerificationProgress() {
        final long seconds = Long.parseLong(System.getProperty(
                "td20.checkerStatsPeriodSeconds", "60"));
        if (seconds <= 0) {
            throw new IllegalArgumentException(
                    "td20.checkerStatsPeriodSeconds must be positive");
        }
        reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("checker-progress").factory());
        reporter.scheduleAtFixedRate(this::report, seconds, seconds, TimeUnit.SECONDS);
    }

    void phase(final String name) {
        phase(() -> name);
    }

    void phase(final Supplier<String> currentStatus) {
        phase(currentStatus, null, null);
    }

    synchronized void phase(final Supplier<String> currentStatus, LongSupplier completed, String unit) {
        status = currentStatus;
        this.completed = completed; this.unit = unit;
        previousCount = completed == null ? 0 : completed.getAsLong();
        previousNanos = System.nanoTime();
        report();
    }

    private synchronized void report() {
        long now = System.nanoTime();
        String throughput = "";
        if (completed != null) {
            long count = completed.getAsLong();
            double seconds = Math.max(1, now - previousNanos) / 1e9;
            throughput = String.format(Locale.ROOT, " throughput=%.0f %s/s",
                    Math.max(0, count - previousCount) / seconds, unit);
            previousCount = count; previousNanos = now;
        }
        String diagnostics = "";
        if (Boolean.getBoolean("td20.checkerVerboseProgress")) {
            final var runtime = Runtime.getRuntime();
            diagnostics = String.format(Locale.ROOT, " elapsed=%.1fs heapMiB=%d/%d",
                    (now - started) / 1e9,
                    (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                    runtime.maxMemory() / (1024 * 1024));
        }
        System.out.printf("%s checker: %s%s%s%n", Instant.now(), status.get(), throughput, diagnostics);
        System.out.flush();
    }

    @Override
    public void close() {
        reporter.shutdownNow();
    }
}
