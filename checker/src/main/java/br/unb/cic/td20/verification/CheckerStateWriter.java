package br.unb.cic.td20.verification;

import lombok.val;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * One bounded queue and one owner of a persistence shard.
 */
final class CheckerStateWriter {

    private static final List<byte[]> END = List.of();
    private final ConcurrentLinkedQueue<List<byte[]>> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore slots;
    private final Semaphore available = new Semaphore(0);
    private final AtomicReference<Throwable> failure;
    private final Thread worker;
    private final LongAdder submittedKeys = new LongAdder(), consumedKeys = new LongAdder();
    private volatile boolean finishing;

    CheckerStateWriter(final int capacity, final String name, final Consumer<List<byte[]>> persist, final Runnable flush) {
        this(capacity, name, persist, flush, new AtomicReference<>());
    }

    CheckerStateWriter(final int capacity,
            final String name,
            final Consumer<List<byte[]>> persist,
            final Runnable flush,
            final AtomicReference<Throwable> failure) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Positive writer queue capacity required");
        }

        this.failure = failure;
        slots = new Semaphore(capacity);
        worker = Thread.ofPlatform().daemon(true).name(name).unstarted(() -> {
            try {
                for (;;) {
                    if (!available.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                        check();
                        continue;
                    }

                    check();

                    val batch = queue.remove();
                    slots.release();
                    if (batch == END) {
                        break;
                    }

                    persist.accept(batch);
                    consumedKeys.add(batch.size());
                }

                check();
                flush.run();
            } catch (final Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                queue.clear();
            }
        });
        worker.start();
    }

    void submit(final List<byte[]> keys) {
        if (keys.isEmpty()) {
            return;
        }

        try {
            for (;;) {
                check();
                if (finishing) {
                    throw new IllegalStateException("State writer is finishing");
                }
                if (!slots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    continue;
                }

                publish(keys);
                check();

                return;
            }
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reconstruction enqueue", error);
        }
    }

    boolean trySubmit(final List<byte[]> keys) {
        if (keys.isEmpty()) {
            return true;
        }

        check();
        if (finishing) {
            throw new IllegalStateException("State writer is finishing");
        }
        if (!slots.tryAcquire()) {
            return false;
        }

        publish(keys);

        check();

        return true;
    }

    private void publish(final List<byte[]> keys) {
        try {
            check();
            if (finishing) {
                throw new IllegalStateException("State writer is finishing");
            }

            // Producers reuse their lists; encoded byte arrays are never mutated.
            queue.add(List.copyOf(keys));
            submittedKeys.add(keys.size());
            available.release();
        } catch (final RuntimeException | Error error) {
            slots.release();
            throw error;
        }
    }

    void check() {
        val error = failure.get();
        if (error != null) {
            throw new IllegalStateException("Reconstruction writer failed", error);
        }
    }

    /**
     * Called after every producer has joined; native resources outlive this join.
     */
    synchronized void requestFinish() {
        boolean interrupted = false;
        if (!finishing) {
            finishing = true;
            while (failure.get() == null) {
                try {
                    if (slots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                        queue.add(END);
                        available.release();
                        break;
                    }
                } catch (final InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    long submittedKeys() {
        return submittedKeys.sum();
    }

    long consumedKeys() {
        return consumedKeys.sum();
    }

    boolean finished() {
        return !worker.isAlive();
    }

    void finish() {
        requestFinish();

        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (final InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        check();
        if (interrupted) {
            throw new IllegalStateException("Interrupted reconstruction writer join");
        }
    }
}
