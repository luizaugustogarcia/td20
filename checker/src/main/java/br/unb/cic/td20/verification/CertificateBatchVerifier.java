package br.unb.cic.td20.verification;

import br.unb.cic.td20.certificate.CertificateStore;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.runtime.CheckerParallelism;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Large bounded multiGet batches whose replay overlaps concurrent generation. */
final class CertificateBatchVerifier implements Consumer<PrimitivePair>, AutoCloseable {
    private final CertificateStore certificates;
    private final int maximumMoves;
    private final Consumer<PrimitivePair> missing;
    private final int batchSize = CheckerParallelism.lookupBatchSize();
    private final Semaphore pipelines = new Semaphore(CheckerParallelism.lookupPipelines());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private ArrayList<PrimitivePair> pending = new ArrayList<>(batchSize);
    private boolean closed;

    CertificateBatchVerifier(final CertificateStore certificates, final int maximumMoves,
                             final Consumer<PrimitivePair> missing) {
        this.certificates = Objects.requireNonNull(certificates);
        this.maximumMoves = maximumMoves;
        this.missing = Objects.requireNonNull(missing);
    }

    @Override public void accept(final PrimitivePair pair) {
        final List<PrimitivePair> batch;
        synchronized (this) {
            check();
            if (closed) throw new IllegalStateException("Certificate batch verifier is closed");
            pending.add(pair);
            if (pending.size() < batchSize) return;
            acquirePipeline();
            batch = pending;
            pending = new ArrayList<>(batchSize);
        }
        process(batch);
    }

    private void process(final List<PrimitivePair> batch) {
        try {
            final boolean[] present = certificates.findAllPrimitive(batch, maximumMoves);
            if (present.length != batch.size())
                throw new IllegalStateException("Incomplete certificate batch result");
            for (int i = 0; i < present.length; i++) if (!present[i]) missing.accept(batch.get(i));
        } catch (final Throwable error) {
            failure.compareAndSet(null, error);
            throw propagate(error);
        } finally {
            pipelines.release();
        }
    }

    private void acquirePipeline() {
        try { pipelines.acquire(); }
        catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for certificate batch capacity", error);
        }
    }

    void check() {
        final var error = failure.get();
        if (error != null) throw propagate(error);
    }

    @Override public void close() {
        final List<PrimitivePair> batch;
        synchronized (this) {
            if (closed) { check(); return; }
            closed = true;
            check();
            if (pending.isEmpty()) return;
            acquirePipeline();
            batch = pending;
            pending = new ArrayList<>();
        }
        process(batch);
        check();
    }

    private static RuntimeException propagate(final Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Certificate batch verification failed", error);
    }
}
