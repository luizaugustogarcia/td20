package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advisory final-root searches alongside inverse persistence. Every canonical
 * root is still checked by the final inventory scan, including dropped batches
 * and keys skipped by the bounded recent-seen cache.
 */
public final class RootPrefetch implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RootPrefetch.class);
    private static final int BATCH_KEYS = 65536;
    private static final int STOPPED = Integer.MIN_VALUE;
    private static final byte[] EMPTY = {};
    private final int gpuWorkers;
    private final BiPredicate<CyclicTargetPair, Integer> gpu;
    private final Function<List<DiskStateSet.State>, boolean[]> lookup;
    private final int preparationWorkers;
    private final ConcurrentLinkedQueue<List<DiskStateSet.State>> preparedQueue = new ConcurrentLinkedQueue<>();
    private final Semaphore preparedSlots, preparedAvailable = new Semaphore(0);
    private static final int PREPARED_BATCH_KEYS = 4096;
    private final ConcurrentLinkedQueue<List<byte[]>> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore slots, available = new Semaphore(0), seenSlots;
    private final ConcurrentHashMap<ByteBuffer, Boolean> seen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ByteBuffer> seenOrder = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger publishers = new AtomicInteger();
    private final LongAdder accepted = new LongAdder(), examined = new LongAdder();
    private final LongAdder searched = new LongAdder(), closed = new LongAdder(), dropped = new LongAdder();
    private final LongAdder completed = new LongAdder(), cached = new LongAdder(), recentSkipped = new LongAdder();
    private final LongAdder prepared = new LongAdder(), gpuCompleted = new LongAdder();
    private final java.util.concurrent.ScheduledExecutorService reporter =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true)
                    .name("root-prefetch-progress").factory());
    private volatile boolean stopping;
    private ExecutorService workers;
    private ExecutorService preparers;
    private boolean finished;

    public RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu) {
        this(gpuWorkers, gpu, null);
    }

    /** GPU callers consume prepared cache misses; preparation never waits on a GPU result. */
    public RootPrefetch(int gpuSlots, BiPredicate<CyclicTargetPair, Integer> gpu,
                        Function<List<DiskStateSet.State>, boolean[]> lookup) {
        this(configuredCallers(gpuSlots), gpu,
                property("td20.rootPrefetchQueueBatches", 256), property("td20.rootPrefetchSeenKeys", 1048576), lookup);
    }

    RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu,
                 int queueBatches, int seenKeys) {
        this(gpuWorkers, gpu, queueBatches, seenKeys, null);
    }

    RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu,
                 int queueBatches, int seenKeys, Function<List<DiskStateSet.State>, boolean[]> lookup) {
        if (gpuWorkers < 1 || queueBatches < 1 || seenKeys < 1)
            throw new IllegalArgumentException("Positive root-prefetch capacities required");
        this.gpuWorkers = gpuWorkers;
        this.gpu = Objects.requireNonNull(gpu);
        this.lookup = lookup;
        preparationWorkers = property("td20.rootPrefetchPreparationWorkers",
                Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
        preparedSlots = new Semaphore(property("td20.rootPrefetchPreparedQueueBatches", 1024));
        slots = new Semaphore(queueBatches);
        seenSlots = new Semaphore(seenKeys);
    }

    private static int property(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int configuredCallers(int gpuSlots) {
        if (gpuSlots < 1) throw new IllegalArgumentException("Positive GPU slot count required");
        return property("td20.rootPrefetchWorkers",Math.max(gpuSlots,ReconstructionPipeline.configuredGpuWorkers()));
    }

    /** Copies batch references without waiting for queue capacity. Keys are immutable. */
    public void publish(List<byte[]> encodedRootKeys) {
        Objects.requireNonNull(encodedRootKeys);
        check();
        for (;;) {
            int count = publishers.get();
            if (count < 0 || stopping) return;
            if (count == Integer.MAX_VALUE) throw new IllegalStateException("Too many root publishers");
            if (publishers.compareAndSet(count, count + 1)) break;
        }
        try {
            for (int first = 0; first < encodedRootKeys.size();) {
                check();
                if (stopping || !slots.tryAcquire()) {
                    dropped.add(encodedRootKeys.size() - first);
                    return;
                }
                int end = first + Math.min(BATCH_KEYS, encodedRootKeys.size() - first);
                try {
                    queue.add(List.copyOf(encodedRootKeys.subList(first, end)));
                    accepted.add(end - first);
                    available.release();
                } catch (RuntimeException | Error error) {
                    slots.release();
                    throw error;
                }
                first = end;
            }
            check();
        } finally { publishers.decrementAndGet(); }
    }

    /** Starts advisory workers. Repeated activation is harmless. */
    public synchronized void activate() {
        check();
        if (stopping || workers != null) return;
        log.info("Root GPU prefetch activated: preparation workers={}, GPU callers={}, queued keys={}",
                preparationWorkers, gpuWorkers, accepted.sum());
        reporter.scheduleWithFixedDelay(this::report, 10, 10, TimeUnit.SECONDS);
        workers = Executors.newFixedThreadPool(gpuWorkers,
                Thread.ofPlatform().name("root-prefetch-", 0).factory());
        preparers = Executors.newFixedThreadPool(preparationWorkers,
                Thread.ofPlatform().name("root-prefetch-prepare-", 0).factory());
        for (int i = 0; i < gpuWorkers; i++) workers.submit(this::searchPrepared);
        for (int i = 0; i < preparationWorkers; i++) preparers.submit(this::consume);
    }

    private void consume() {
        try {
            while (!stopping) {
                if (!available.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
                if (stopping) return;
                var batch = queue.poll();
                if (batch == null) continue;
                slots.release();
                var states = new ArrayList<DiskStateSet.State>(batch.size());
                for (byte[] key : batch) {
                    if (stopping) return;
                    examined.increment();
                    if (!remember(key)) { recentSkipped.increment(); completed.increment(); continue; }
                    var state = DiskStateSet.decode(key, EMPTY);
                    if (state.maximumMoves() != ErikssonRoute.ROOT_BUDGET
                            || state.pair().getBeta().size() != ErikssonRoute.ROOT_SYMBOLS)
                        throw new IllegalArgumentException("Root prefetch requires 21 symbols and budget 11");
                    states.add(state);
                }
                if (states.isEmpty() || stopping) continue;
                boolean[] hits = lookup == null ? new boolean[states.size()] : lookup.apply(List.copyOf(states));
                if (hits == null || hits.length != states.size())
                    throw new IllegalStateException("Incomplete root-prefetch certificate lookup");
                var misses = new ArrayList<DiskStateSet.State>(PREPARED_BATCH_KEYS);
                for (int i = 0; i < states.size(); i++) {
                    if (stopping) return;
                    if (hits[i]) { cached.increment(); completed.increment(); continue; }
                    misses.add(states.get(i));
                    if (misses.size() == PREPARED_BATCH_KEYS) { publishPrepared(misses); misses.clear(); }
                }
                if (!misses.isEmpty()) publishPrepared(misses);
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure.compareAndSet(null, error);
            stopping = true;
            available.release(preparationWorkers); preparedAvailable.release(gpuWorkers);
        }
    }

    private void publishPrepared(List<DiskStateSet.State> states) throws InterruptedException {
        while (!stopping) {
            check();
            if (!preparedSlots.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
            if (stopping) { preparedSlots.release(); return; }
            preparedQueue.add(List.copyOf(states)); prepared.add(states.size()); preparedAvailable.release();
            return;
        }
    }

    private void searchPrepared() {
        try {
            while (!stopping) {
                if (!preparedAvailable.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
                if (stopping) return;
                var batch = preparedQueue.poll();
                if (batch == null) continue;
                preparedSlots.release();
                for (var state : batch) {
                    if (stopping) return;
                    searched.increment();
                    if (gpu.test(state.pair(), state.maximumMoves())) closed.increment();
                    gpuCompleted.increment(); completed.increment();
                }
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure.compareAndSet(null, error); stopping = true;
            available.release(preparationWorkers); preparedAvailable.release(gpuWorkers);
        }
    }

    /** A cache miss may remain uncached during concurrent eviction; it is still searched. */
    private boolean remember(byte[] bytes) {
        var key = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        if (seen.containsKey(key)) return false;
        if (!seenSlots.tryAcquire()) {
            var oldest = seenOrder.poll();
            if (oldest == null) return true;
            if (seen.remove(oldest) != null) seenSlots.release();
            if (!seenSlots.tryAcquire()) return true;
        }
        if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
            seenSlots.release();
            return false;
        }
        seenOrder.add(key);
        return true;
    }

    public void check() {
        var error = failure.get();
        if (error != null) throw new IllegalStateException("Root-prefetch search failed", error);
    }

    private void report() {
        long done = completed.sum(), examinedCount = examined.sum(), total = accepted.sum();
        long gpuDone = gpuCompleted.sum(), gpuSent = searched.sum(), ready = prepared.sum();
        long discarded = finished ? total - done : 0;
        log.info("Root GPU prefetch (advisory keys): processed={}/{} ({}%), remaining={}, examined={}, cached={}, "
                        + "recentSkipped={}, searched={}, closed={}, encodedQueued={}, preparedRemaining={}, inFlight={}, discarded={}, dropped={}, active={}",
                done, total, String.format(Locale.ROOT, "%.2f", total == 0 ? 100 : 100.0 * done / total),
                total - done - discarded, examinedCount, cached.sum(), recentSkipped.sum(), gpuSent, closed.sum(),
                finished ? 0 : total - examinedCount, finished ? 0 : ready - gpuDone,
                gpuSent - gpuDone, discarded, dropped.sum(), !stopping);
    }

    /** Discards optional backlog and joins in-flight GPU calls before final scanning. */
    public synchronized void finish() {
        boolean interrupted = false;
        if (!finished) {
            stopping = true;
            publishers.getAndUpdate(count -> count | STOPPED);
            while (publishers.get() != STOPPED) {
                if (Thread.interrupted()) interrupted = true;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            queue.clear();
            preparedQueue.clear();
            available.release(preparationWorkers); preparedAvailable.release(gpuWorkers);
            if (preparers != null) preparers.shutdown();
            if (workers != null) {
                workers.shutdown();
                for (;;) {
                    try { if (workers.awaitTermination(1, TimeUnit.SECONDS)) break; }
                    catch (InterruptedException error) { interrupted = true; }
                }
            }
            if (preparers != null) {
                for (;;) {
                    try { if (preparers.awaitTermination(1, TimeUnit.SECONDS)) break; }
                    catch (InterruptedException error) { interrupted = true; }
                }
            }
            queue.clear();
            preparedQueue.clear();
            seen.clear();
            seenOrder.clear();
            dropped.add(accepted.sum() - completed.sum());
            finished = true;
            reporter.shutdown();
            report();
        }
        if (interrupted) Thread.currentThread().interrupt();
        check();
        if (interrupted) throw new IllegalStateException("Interrupted while finishing root prefetch");
    }

    @Override public void close() { finish(); }
}
