package br.unb.cic.td20.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded parallel work that joins every worker before its resources can close.
 */
public final class BoundedTasks implements AutoCloseable {

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ThreadPoolExecutor workers;

    public BoundedTasks(final int count, final String threadPrefix) {
        if (count < 1) {
            throw new IllegalArgumentException("Positive worker count required");
        }

        workers = new ThreadPoolExecutor(count,
                count,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256),
                Thread.ofPlatform().name(threadPrefix, 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void submit(final Runnable task) {
        check();
        workers.execute(() -> {
            if (failure.get() != null) {
                return;
            }

            try {
                task.run();
            } catch (final Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        check();
    }

    public void check() {
        if (failure.get() != null) {
            throw new IllegalStateException("Emission worker failed", failure.get());
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Emission interrupted");
        }
    }

    @Override
    public void close() {
        workers.shutdown();

        boolean interrupted = false;
        for (;;) {
            try {
                if (workers.awaitTermination(1, TimeUnit.SECONDS)) {
                    break;
                }
                if (failure.get() != null) {
                    workers.shutdownNow();
                }
            } catch (final InterruptedException error) {
                interrupted = true;
                failure.compareAndSet(null, error);
                workers.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        check();
    }
}
