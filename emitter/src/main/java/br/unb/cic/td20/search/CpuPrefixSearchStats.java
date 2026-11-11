package br.unb.cic.td20.search;

import br.unb.cic.td20.certificate.SortingCertificate;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Windowed CPU prefix throughput, separate from GPU fallthrough throughput. */
final class CpuPrefixSearchStats {
    private static final Logger log = LoggerFactory.getLogger(CpuPrefixSearchStats.class);
    private static volatile CpuPrefixSearchStats instance;
    private final ScheduledExecutorService reporter;
    private long started = System.nanoTime();
    private long completed, closed, completeWitnesses, elapsedNanos, prefixDepth, failures;

    static CpuPrefixSearchStats instance() {
        var current = instance;
        if (current == null) synchronized (CpuPrefixSearchStats.class) {
            current = instance;
            if (current == null) instance = current = new CpuPrefixSearchStats();
        }
        return current;
    }

    private CpuPrefixSearchStats() {
        long seconds = Long.getLong("td20.cpuStatsPeriodSeconds",
                Long.getLong("td20.gpuStatsPeriodSeconds", 10L));
        if (seconds < 0) throw new IllegalArgumentException("CPU statistics period cannot be negative");
        reporter = seconds == 0 ? null : Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("cpu-prefix-stats").factory());
        if (reporter != null) reporter.scheduleAtFixedRate(this::report, seconds, seconds, TimeUnit.SECONDS);
    }

    synchronized void completed(Optional<SortingCertificate> result, long duration) {
        completed++;
        elapsedNanos += duration;
        if (result.isPresent()) {
            closed++;
            prefixDepth += result.orElseThrow().moves().size();
            if (result.orElseThrow().kind() == SortingCertificate.Kind.COMPLETE) completeWitnesses++;
        }
    }

    synchronized void failed(long duration) { completed++; failures++; elapsedNanos += duration; }

    private synchronized void report() {
        long now = System.nanoTime();
        double seconds = Math.max(1, now - started) / 1_000_000_000.0;
        log.info("CPU prefix stats (last {}s): completed={}, settled={}, complete witnesses={}, "
                        + "GPU fallthroughs={}, throughput={}/s, closure rate={}, avg search={} ms, "
                        + "avg prefix depth={}, failures={}",
                format(seconds), completed, closed, completeWitnesses, completed - closed - failures,
                format(completed / seconds), format(completed == 0 ? 0 : 100.0 * closed / completed) + "%",
                format(completed == 0 ? 0 : elapsedNanos / 1_000_000.0 / completed),
                format(closed == 0 ? 0 : (double) prefixDepth / closed), failures);
        completed = closed = completeWitnesses = elapsedNanos = prefixDepth = failures = 0;
        started = now;
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.3f", value); }

    static synchronized void shutdown() {
        if (instance != null) {
            if (instance.reporter != null) instance.reporter.shutdownNow();
            instance = null;
        }
    }
}
