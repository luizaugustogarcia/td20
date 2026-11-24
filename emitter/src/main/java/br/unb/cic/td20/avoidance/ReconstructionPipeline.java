package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.runtime.BoundedTasks;
import lombok.val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * CPU work never waits for GPU capacity. Only CPU misses enter the disk-backed handoff.
 */
public final class ReconstructionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReconstructionPipeline.class);

    private ReconstructionPipeline() {
    }

    public static int configuredGpuWorkers() {
        val layout = new GpuDeviceLayout(GpuDeviceLayout.parseDevices(System.getProperty("td20.gpuDevices", "0")),
                Integer.getInteger("td20.gpuSlots", 8));
        final String queue = System.getProperty("td20.gpuQueueCapacity", "").trim();
        final int capacity = queue.isEmpty() ? 0 : Integer.parseInt(queue);
        if (capacity < 0) {
            throw new IllegalArgumentException("GPU queue capacity must not be negative");
        }

        final int workers = Integer.getInteger("td20.reconstructionGpuWorkers",
                Math.addExact(Math.multiplyExact(2, layout.totalSlots()), capacity));
        if (workers < 1) {
            throw new IllegalArgumentException("Reconstruction GPU workers must be positive");
        }

        return workers;
    }

    public static void process(final DiskStateSet states,
            final Path spool,
            final int cpuWorkers,
            final int gpuWorkers,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<CyclicTargetPair, Integer> cpu,
            final BiPredicate<CyclicTargetPair, Integer> gpu,
            final Consumer<DiskStateSet.State> residual) {
        process(states, spool, cpuWorkers, gpuWorkers, lookup, cpu, gpu, residual, () -> {
        });
    }

    public static void process(final DiskStateSet states,
            final Path spool,
            final int cpuWorkers,
            final int gpuWorkers,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<CyclicTargetPair, Integer> cpu,
            final BiPredicate<CyclicTargetPair, Integer> gpu,
            final Consumer<DiskStateSet.State> residual,
            final Runnable gpuDrained) {
        processPrimitive(states,
                spool,
                cpuWorkers,
                gpuWorkers,
                lookup,
                (pair, budget) -> cpu.test(LegacyPairs.toLegacy(pair), budget),
                (pair, budget) -> gpu.test(LegacyPairs.toLegacy(pair), budget),
                residual,
                gpuDrained);
    }

    public static void processPrimitive(final DiskStateSet states,
            final Path spool,
            final int cpuWorkers,
            final int gpuWorkers,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Consumer<DiskStateSet.State> residual) {
        processPrimitive(states, spool, cpuWorkers, gpuWorkers, lookup, cpu, gpu, residual, () -> {
        });
    }

    public static void processPrimitive(final DiskStateSet states,
            final Path spool,
            final int cpuWorkers,
            final int gpuWorkers,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Consumer<DiskStateSet.State> residual,
            final Runnable gpuDrained) {
        processPrimitive(states, spool, cpuWorkers, gpuWorkers, lookup, cpu, gpu, residual, gpuDrained, () -> "");
    }

    public static void processPrimitive(final DiskStateSet states,
            final Path spool,
            final int cpuWorkers,
            final int gpuWorkers,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Consumer<DiskStateSet.State> residual,
            final Runnable gpuDrained,
            final Supplier<String> details) {
        val total = new AtomicLong(states.prepareScan());
        final int inverseWorkers = Integer.getInteger("td20.reconstructionInverseWorkers",
                Math.min(cpuWorkers, Runtime.getRuntime().availableProcessors()));
        if (inverseWorkers < 1) {
            throw new IllegalArgumentException("Inverse workers must be positive");
        }

        val cached = new LongAdder();
        val cpuClosed = new LongAdder();
        val gpuClosed = new LongAdder();
        val inverseCompleted = new LongAdder();

        try (val queue = new DiskStateQueue(spool);
            val inverseQueue = new DiskStateQueue(spool.resolveSibling(spool.getFileName() + ".inverse.queue"));
            val reporter = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("reconstruction-progress").factory())) {
            final Runnable report = () -> {
                final long cachedCount = cached.sum(), cpuCount = cpuClosed.sum(), gpuCount = gpuClosed.sum();
                final long inverseCount = inverseCompleted.sum(), submitted = queue.appended(), misses = inverseQueue.appended();
                final long processed = cachedCount + cpuCount + gpuCount + inverseCount;
                final long expected = total.get();
                final String percent = expected < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f", expected == 0 ? 100.0 : 100.0 * processed / expected);
                final String extra = details.get();
                log.info("Inverse reconstruction {} search/inverse: processed={}/{} ({}%), remaining={}, cached={}, cpuClosed={}, " + "cpuRemaining={}, gpuSubmitted={}, gpuClosed={}, gpuMisses={}, gpuRemaining={}, inverseCompleted={}, inverseRemaining={}{}",
                        spool.getFileName(),
                        processed,
                        expected < 0 ? "unknown" : expected,
                        percent,
                        expected < 0 ? "unknown" : expected - processed,
                        cachedCount,
                        cpuCount,
                        expected < 0 ? "unknown" : expected - cachedCount - cpuCount - submitted,
                        submitted,
                        gpuCount,
                        misses,
                        submitted - gpuCount - misses,
                        inverseCount,
                        misses - inverseCount,
                        extra.isEmpty() ? "" : ", " + extra);
            };
            log.info("Reconstruction pipeline {}: CPU workers={}, GPU workers={}, inverse workers={}, disk queue={}",
                    spool.getFileName(),
                    cpuWorkers,
                    gpuWorkers,
                    inverseWorkers,
                    spool);
            report.run();
            reporter.scheduleWithFixedDelay(report, 10, 10, TimeUnit.SECONDS);

            try (val inverseConsumers = new BoundedTasks(inverseWorkers, "reconstruction-inverse-")) {
                for (int i = 0; i < inverseWorkers; i++) {
                    inverseConsumers.submit(() -> {
                        try {
                            for (DiskStateSet.State state; (state = inverseQueue.take()) != null; ) {
                                queue.check();
                                residual.accept(state);
                                inverseCompleted.increment();
                            }
                        } catch (final RuntimeException | Error error) {
                            queue.abort(error);
                            inverseQueue.abort(error);
                            throw error;
                        }
                    });
                }

                try {
                    try (val consumers = new BoundedTasks(gpuWorkers, "reconstruction-gpu-")) {
                        for (int i = 0; i < gpuWorkers; i++) {
                            consumers.submit(() -> {
                                try {
                                    for (DiskStateSet.State state; (state = queue.take()) != null; ) {
                                        final boolean closed = gpu.test(state.primitivePair(), state.maximumMoves());
                                        queue.check();
                                        if (closed) {
                                            gpuClosed.increment();
                                        } else {
                                            inverseQueue.add(state);
                                        }
                                    }
                                } catch (final RuntimeException | Error error) {
                                    queue.abort(error);
                                    inverseQueue.abort(error);
                                    throw error;
                                }
                            });
                        }

                        try {
                            states.forEachConcurrently(cpuWorkers,
                                    lookup,
                                    state -> {
                                        queue.check();
                                        cached.increment();
                                    },
                                    state -> {
                                        queue.check();
                                        if (cpu.test(state.primitivePair(), state.maximumMoves())) {
                                            cpuClosed.increment();
                                        } else {
                                            queue.add(state);
                                        }
                                    });
                            total.set(states.knownSize());
                            if (total.get() < 0) {
                                throw new IllegalStateException("Missing exhaustive source inventory");
                            }

                            queue.finish();
                        } catch (final RuntimeException | Error error) {
                            queue.abort(error);
                            inverseQueue.abort(error);
                            throw error;
                        }
                    }

                    queue.check();
                    gpuDrained.run();
                    inverseQueue.finish();
                } catch (final RuntimeException | Error error) {
                    queue.abort(error);
                    inverseQueue.abort(error);
                    throw error;
                }
            }

            queue.check();
            inverseQueue.check();
            if (cached.sum() + cpuClosed.sum() + queue.appended() != total.get()
                    || gpuClosed.sum() + inverseQueue.appended() != queue.appended()
                    || inverseCompleted.sum() != inverseQueue.appended()) {
                throw new IllegalStateException("Incomplete reconstruction pipeline coverage");
            }
            report.run();
        }
    }
}
