package br.unb.cic.td20.fleet;

import java.util.*;
import java.util.concurrent.*;

/** Bounded background transfers; the phase barrier observes every transfer failure. */
final class FleetDownloads implements AutoCloseable {
    @FunctionalInterface interface Transfer { void run() throws Exception; }
    private final ThreadPoolExecutor executor;
    private final List<Future<?>> transfers = new ArrayList<>();

    FleetDownloads(int workers, int capacity) {
        if (workers < 1 || capacity < 1) throw new IllegalArgumentException("Positive download worker and queue counts required");
        executor = new ThreadPoolExecutor(workers,workers,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("fleet-download-",0).factory(),(task,pool) -> {
                    if (pool.isShutdown()) throw new RejectedExecutionException("Download queue is closed");
                    try { pool.getQueue().put(task); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RejectedExecutionException(error); }
                });
    }
    synchronized void submit(Transfer transfer) {
        transfers.add(executor.submit(() -> {
            try { transfer.run(); } catch (Exception error) { throw new CompletionException(error); }
        }));
    }
    void await() throws Exception {
        List<Future<?>> pending;
        synchronized (this) { pending = List.copyOf(transfers); }
        Exception failure = null;
        for (var transfer : pending) try { transfer.get(); }
        catch (ExecutionException error) {
            if (failure == null) failure = new IllegalStateException("Background download failed; remote results and accepted downloads are retained",error.getCause());
            else failure.addSuppressed(error.getCause());
        }
        if (failure != null) throw failure;
    }
    @Override public void close() { executor.close(); }
}
