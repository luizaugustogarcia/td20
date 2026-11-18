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
    private final int cpuWorkers, gpuWorkers;
    private final BiPredicate<CyclicTargetPair, Integer> cpu;
    private final BiPredicate<CyclicTargetPair, Integer> gpu;
    private final Function<List<DiskStateSet.State>, boolean[]> lookup;
    private final int preparationWorkers;
    private final ConcurrentLinkedQueue<List<DiskStateSet.State>> preparedQueue = new ConcurrentLinkedQueue<>();
    private final Semaphore preparedSlots, preparedAvailable = new Semaphore(0);
    private final ConcurrentLinkedQueue<List<DiskStateSet.State>> gpuQueue = new ConcurrentLinkedQueue<>();
    private final Semaphore gpuSlots, gpuAvailable = new Semaphore(0);
    private static final int PREPARED_BATCH_KEYS = 4096;
    private final ConcurrentLinkedQueue<List<byte[]>> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore slots, available = new Semaphore(0), seenSlots;
    private final ConcurrentHashMap<ByteBuffer, Boolean> seen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ByteBuffer> seenOrder = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger publishers = new AtomicInteger();
    private final LongAdder accepted = new LongAdder(), examined = new LongAdder();
    private final LongAdder cpuSearched = new LongAdder(), cpuClosed = new LongAdder();
    private final LongAdder searched = new LongAdder(), closed = new LongAdder(), dropped = new LongAdder();
    private final LongAdder completed = new LongAdder(), cached = new LongAdder(), recentSkipped = new LongAdder();
    private final LongAdder prepared = new LongAdder(), cpuCompleted = new LongAdder();
    private final LongAdder gpuPrepared = new LongAdder(), gpuCompleted = new LongAdder();
    private final java.util.concurrent.ScheduledExecutorService reporter =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true)
                    .name("root-prefetch-progress").factory());
    private volatile boolean stopping;
    private ExecutorService workers;
    private ExecutorService prefixWorkers;
    private ExecutorService preparers;
    private boolean finished;

    public RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu) {
        this(gpuWorkers, 1, (pair,budget) -> false, gpu,
                property("td20.rootPrefetchQueueBatches", 256), property("td20.rootPrefetchSeenKeys", 1048576), null);
    }

    /** Batched lookup misses pass through CPU-prefix workers before independent GPU callers. */
    public RootPrefetch(int gpuSlots, BiPredicate<CyclicTargetPair, Integer> gpu,
                        Function<List<DiskStateSet.State>, boolean[]> lookup) {
        this(gpuSlots, (pair,budget) -> false, gpu, lookup);
    }

    public RootPrefetch(int gpuSlots, BiPredicate<CyclicTargetPair, Integer> cpu,
                        BiPredicate<CyclicTargetPair, Integer> gpu,
                        Function<List<DiskStateSet.State>, boolean[]> lookup) {
        this(configuredCallers(gpuSlots), property("td20.rootPrefetchCpuWorkers", Runtime.getRuntime().availableProcessors()),
                cpu, gpu, property("td20.rootPrefetchQueueBatches", 256),
                property("td20.rootPrefetchSeenKeys", 1048576), lookup);
    }

    RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu,
                 int queueBatches, int seenKeys) {
        this(gpuWorkers, 1, (pair,budget) -> false, gpu, queueBatches, seenKeys, null);
    }

    RootPrefetch(int gpuWorkers, BiPredicate<CyclicTargetPair, Integer> gpu,
                 int queueBatches, int seenKeys, Function<List<DiskStateSet.State>, boolean[]> lookup) {
        this(gpuWorkers, 1, (pair,budget) -> false, gpu, queueBatches, seenKeys, lookup);
    }

    RootPrefetch(int gpuWorkers, int cpuWorkers, BiPredicate<CyclicTargetPair, Integer> cpu,
                 BiPredicate<CyclicTargetPair, Integer> gpu, int queueBatches, int seenKeys,
                 Function<List<DiskStateSet.State>, boolean[]> lookup) {
        if (cpuWorkers < 1 || gpuWorkers < 1 || queueBatches < 1 || seenKeys < 1)
            throw new IllegalArgumentException("Positive root-prefetch capacities required");
        this.cpuWorkers = cpuWorkers;
        this.gpuWorkers = gpuWorkers;
        this.cpu = Objects.requireNonNull(cpu);
        this.gpu = Objects.requireNonNull(gpu);
        this.lookup = lookup;
        preparationWorkers = property("td20.rootPrefetchPreparationWorkers",
                Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
        preparedSlots = new Semaphore(property("td20.rootPrefetchPreparedQueueBatches", 1024));
        gpuSlots = new Semaphore(property("td20.rootPrefetchGpuQueueBatches", 1024));
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
        log.info("Root prefetch activated: batched-lookup workers={}, CPU-prefix workers={}, GPU callers={}, queued keys={}",
                preparationWorkers, cpuWorkers, gpuWorkers, accepted.sum());
        reporter.scheduleWithFixedDelay(this::report, 10, 10, TimeUnit.SECONDS);
        workers = Executors.newFixedThreadPool(gpuWorkers,
                Thread.ofPlatform().name("root-prefetch-", 0).factory());
        prefixWorkers = Executors.newFixedThreadPool(cpuWorkers,
                Thread.ofPlatform().name("root-prefetch-cpu-", 0).factory());
        preparers = Executors.newFixedThreadPool(preparationWorkers,
                Thread.ofPlatform().name("root-prefetch-prepare-", 0).factory());
        for (int i = 0; i < gpuWorkers; i++) workers.submit(this::searchGpuPrepared);
        for (int i = 0; i < cpuWorkers; i++) prefixWorkers.submit(this::searchCpuPrepared);
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
            available.release(preparationWorkers); preparedAvailable.release(cpuWorkers); gpuAvailable.release(gpuWorkers);
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

    private void searchCpuPrepared() {
        try {
            while (!stopping) {
                if (!preparedAvailable.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
                if (stopping) return;
                var batch = preparedQueue.poll();
                if (batch == null) continue;
                preparedSlots.release();
                var misses = new ArrayList<DiskStateSet.State>(PREPARED_BATCH_KEYS);
                for (var state : batch) {
                    if (stopping) return;
                    cpuSearched.increment();
                    if (cpu.test(state.pair(), state.maximumMoves())) {
                        cpuClosed.increment(); completed.increment();
                    } else {
                        misses.add(state);
                        if (misses.size() == PREPARED_BATCH_KEYS) { publishGpu(misses); misses.clear(); }
                    }
                    cpuCompleted.increment();
                }
                if (!misses.isEmpty()) publishGpu(misses);
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure.compareAndSet(null, error); stopping = true;
            available.release(preparationWorkers); preparedAvailable.release(cpuWorkers); gpuAvailable.release(gpuWorkers);
        }
    }

    private void publishGpu(List<DiskStateSet.State> states) throws InterruptedException {
        while (!stopping) {
            check();
            if (!gpuSlots.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
            if (stopping) { gpuSlots.release(); return; }
            gpuQueue.add(List.copyOf(states)); gpuPrepared.add(states.size()); gpuAvailable.release();
            return;
        }
    }

    private void searchGpuPrepared() {
        try {
            while (!stopping) {
                if (!gpuAvailable.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
                if (stopping) return;
                var batch = gpuQueue.poll();
                if (batch == null) continue;
                gpuSlots.release();
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
            available.release(preparationWorkers); preparedAvailable.release(cpuWorkers); gpuAvailable.release(gpuWorkers);
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
        long cpuDone = cpuCompleted.sum(), cpuSent = cpuSearched.sum(), ready = prepared.sum();
        long gpuDone = gpuCompleted.sum(), gpuSent = searched.sum(), gpuReady = gpuPrepared.sum();
        long discarded = finished ? total - done : 0;
        log.info("Root prefetch (advisory keys): processed={}/{} ({}%), remaining={}, examined={}, cached={}, "
                        + "recentSkipped={}, cpuSearched={}, cpuClosed={}, gpuSearched={}, gpuClosed={}, encodedQueued={}, "
                        + "cpuPreparedRemaining={}, cpuInFlight={}, gpuPreparedRemaining={}, gpuInFlight={}, discarded={}, dropped={}, active={}",
                done, total, String.format(Locale.ROOT, "%.2f", total == 0 ? 100 : 100.0 * done / total),
                total - done - discarded, examinedCount, cached.sum(), recentSkipped.sum(), cpuSent, cpuClosed.sum(),
                gpuSent, closed.sum(), finished ? 0 : total - examinedCount, finished ? 0 : ready - cpuDone,
                cpuSent - cpuDone, finished ? 0 : gpuReady - gpuDone, gpuSent - gpuDone,
                discarded, dropped.sum(), !stopping);
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
            gpuQueue.clear();
            available.release(preparationWorkers); preparedAvailable.release(cpuWorkers); gpuAvailable.release(gpuWorkers);
            if (preparers != null) preparers.shutdown();
            if (prefixWorkers != null) prefixWorkers.shutdown();
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
            if (prefixWorkers != null) {
                for (;;) {
                    try { if (prefixWorkers.awaitTermination(1, TimeUnit.SECONDS)) break; }
                    catch (InterruptedException error) { interrupted = true; }
                }
            }
            queue.clear();
            preparedQueue.clear();
            gpuQueue.clear();
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
