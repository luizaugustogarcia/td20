package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.runtime.CheckerParallelism;
import lombok.val;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Worker-owned input buffers and memory-admitted asynchronous certificate batches.
 */
final class CertificateBatchVerifier implements Consumer<PrimitivePair>, AutoCloseable {

    // Conservative accounting for pairs, keys, results, references and replay temporaries.
    // This bounds admitted Java work, rather than claiming an exact JVM object size.
    private static final long BYTES_PER_PAIR = 512;
    private final Function<List<PrimitivePair>, CompletableFuture<boolean[]>> lookup;
    private final Consumer<PrimitivePair> missing;
    private final int batchSize, producerSize;
    private final ArrayBlockingQueue<List<PrimitivePair>> queue;
    private final ConcurrentLinkedQueue<Producer> producers = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Producer> local;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final LongAdder submitted = new LongAdder(), checked = new LongAdder(), hits = new LongAdder();
    private final Object memory = new Object(), completion = new Object();
    private final long memoryLimit;
    private long retainedBytes;
    private int inFlight;
    private volatile boolean inputFinished, closed;
    private final Thread dispatcher;

    CertificateBatchVerifier(final CertificateStore certificates, final int maximumMoves, final Consumer<PrimitivePair> missing) {
        this(pairs -> certificates.findAllPrimitiveAsync(pairs, maximumMoves),
                missing,
                CheckerParallelism.lookupBatchSize(),
                CheckerParallelism.producerBatchSize(),
                CheckerParallelism.bufferedBytes());
    }

    CertificateBatchVerifier(final Function<List<PrimitivePair>, CompletableFuture<boolean[]>> lookup,
            final Consumer<PrimitivePair> missing,
            final int batchSize,
            final int producerSize,
            final long memoryLimit) {
        this.lookup = Objects.requireNonNull(lookup);
        this.missing = Objects.requireNonNull(missing);
        if (batchSize < 1 || producerSize < 1 || memoryLimit < Math.multiplyExact(batchSize, BYTES_PER_PAIR)) {
            throw new IllegalArgumentException("Buffered memory must hold at least one complete certificate batch");
        }

        this.batchSize = batchSize;
        this.producerSize = Math.min(batchSize, producerSize);
        this.memoryLimit = memoryLimit;
        queue = new ArrayBlockingQueue<>((int) Math.min(256, Math.max(2, memoryLimit / (this.producerSize * BYTES_PER_PAIR))));
        local = ThreadLocal.withInitial(() -> {
            val p = new Producer(this.producerSize);
            producers.add(p);

            return p;
        });
        dispatcher = Thread.ofPlatform().daemon(true).name("certificate-batch-dispatch").start(this::dispatch);
    }

    private static final class Producer {

        private final int size;
        private ArrayList<PrimitivePair> pending;

        Producer(final int size) {
            this.size = size;
            pending = new ArrayList<>(size);
        }

        void accept(final PrimitivePair pair, final CertificateBatchVerifier owner) {
            pending.add(pair);
            if (pending.size() == size) {
                flush(owner);
            }
        }

        void flush(final CertificateBatchVerifier owner) {
            if (!pending.isEmpty()) {
                owner.publish(pending);
                pending = new ArrayList<>(size);
            }
        }
    }

    @Override
    public void accept(final PrimitivePair pair) {
        check();
        if (closed) {
            throw new IllegalStateException("Certificate batch verifier is closed");
        }

        local.get().accept(Objects.requireNonNull(pair), this);
    }

    private void publish(final List<PrimitivePair> pairs) {
        final long bytes = pairs.size() * BYTES_PER_PAIR;
        boolean admitted = false, queued = false;

        try {
            synchronized (memory) {
                while (retainedBytes > memoryLimit - bytes) {
                    check();
                    memory.wait(100);
                }

                check();
                retainedBytes += bytes;
                admitted = true;
            }
            // Each producer owns its list. No producer-wide lock is held while waiting.
            while (!queue.offer(pairs, 100, TimeUnit.MILLISECONDS)) {
                check();
            }

            queued = true;
            submitted.add(pairs.size());
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            fail(error);
            throw new IllegalStateException("Certificate producer interrupted", error);
        } finally {
            if (admitted && !queued) {
                release(pairs.size());
            }
        }
    }

    private void dispatch() {
        var pending = new ArrayList<PrimitivePair>(batchSize);

        try {
            while (!inputFinished || !queue.isEmpty()) {
                check();

                var chunk = queue.poll(100, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    // A partial assembler must not retain all credits while producers
                    // wait for enough room to publish their next complete buffer.
                    boolean saturated;
                    synchronized (memory) {
                        saturated = retainedBytes > memoryLimit - producerSize * BYTES_PER_PAIR;
                    }
                    if (!pending.isEmpty() && saturated) {
                        val batch = pending;
                        pending = new ArrayList<>(batchSize);
                        submit(batch);
                    }
                    continue;
                }
                if (chunk.isEmpty()) {
                    continue;
                }
                if (!pending.isEmpty() && pending.size() + chunk.size() > batchSize) {
                    val batch = pending;
                    pending = new ArrayList<>(batchSize);
                    submit(batch);
                }

                pending.addAll(chunk);
                if (pending.size() == batchSize) {
                    val batch = pending;
                    pending = new ArrayList<>(batchSize);
                    submit(batch);
                }
            }
            if (!pending.isEmpty()) {
                val batch = pending;
                pending = new ArrayList<>();
                submit(batch);
            }
        } catch (final Throwable error) {
            fail(error);
        } finally {
            if (!pending.isEmpty()) {
                release(pending.size());
            }
            for (var chunk = queue.poll(); chunk != null; chunk = queue.poll()) {
                release(chunk.size());
            }
            synchronized (completion) {
                while (inFlight != 0) {
                    try {
                        completion.wait();
                    } catch (final InterruptedException error) {
                        fail(error);
                    }
                }
            }
        }
    }

    private void submit(final List<PrimitivePair> batch) {
        synchronized (completion) {
            inFlight++;
        }

        try {
            lookup.apply(batch).whenComplete((present, error) -> {
                try {
                    if (error != null) {
                        throw propagate(error);
                    }
                    if (present.length != batch.size()) {
                        throw new IllegalStateException("Incomplete certificate batch result");
                    }

                    long found = 0;
                    for (int i = 0; i < present.length; i++) {
                        if (present[i]) {
                            found++;
                        } else {
                            missing.accept(batch.get(i));
                        }
                    }

                    hits.add(found);
                    checked.add(batch.size());
                } catch (final Throwable problem) {
                    fail(problem);
                } finally {
                    finished(batch.size());
                }
            });
        } catch (final Throwable error) {
            fail(error);
            finished(batch.size());
        }
    }

    private void finished(final int size) {
        try {
            release(size);
        } finally {
            synchronized (completion) {
                inFlight--;
                completion.notifyAll();
            }
        }
    }

    private void release(final int size) {
        synchronized (memory) {
            retainedBytes -= size * BYTES_PER_PAIR;
            if (retainedBytes < 0) {
                throw new IllegalStateException("Certificate memory accounting underflow");
            }

            memory.notifyAll();
        }
    }

    private void fail(final Throwable error) {
        failure.compareAndSet(null, error);
        synchronized (memory) {
            memory.notifyAll();
        }
    }

    void check() {
        val error = failure.get();
        if (error != null) {
            throw propagate(error);
        }
    }

    long checkedCount() {
        return checked.sum();
    }

    String progress() {
        long bytes;
        synchronized (memory) {
            bytes = retainedBytes;
        }

        return "submitted=" + submitted.sum() + " checked=" + checked.sum() + " witnessHits=" + hits.sum() + " pending=" + Math.max(0, submitted.sum() - checked.sum()) + " bufferedMiB=" + bytes / (1024 * 1024) + "/" + memoryLimit / (1024 * 1024);
    }

    /**
     * Generation joins its workers before close; every private tail and asynchronous result is then drained.
     */
    @Override
    public void close() {
        if (closed) {
            check();
            return;
        }

        closed = true;

        try {
            check();
            for (val producer : producers) {
                producer.flush(this);
            }
        } catch (final Throwable error) {
            fail(error);
        } finally {
            inputFinished = true;
            queue.offer(List.of());
        }

        boolean interrupted = false;
        for (;;) {
            try {
                dispatcher.join();
                break;
            } catch (final InterruptedException error) {
                interrupted = true;
                fail(error);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        check();
        if (checked.sum() != submitted.sum()) {
            throw new IllegalStateException("Incomplete certificate verification");
        }
        synchronized (memory) {
            if (retainedBytes != 0) {
                throw new IllegalStateException("Undrained certificate input");
            }
        }
    }

    private static RuntimeException propagate(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }

        return new IllegalStateException("Certificate batch verification failed", error);
    }
}
