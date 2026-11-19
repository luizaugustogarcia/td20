package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.runtime.BoundedTasks;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CPU work never waits for GPU capacity. Only CPU misses enter the disk-backed handoff. */
public final class ReconstructionPipeline {
    private static final Logger log = LoggerFactory.getLogger(ReconstructionPipeline.class);
    private ReconstructionPipeline() { }

    public static int configuredGpuWorkers() {
        var layout = new GpuDeviceLayout(GpuDeviceLayout.parseDevices(
                System.getProperty("td20.gpuDevices", "0")), Integer.getInteger("td20.gpuSlots", 8));
        String queue = System.getProperty("td20.gpuQueueCapacity", "").trim();
        int capacity = queue.isEmpty() ? 0 : Integer.parseInt(queue);
        if (capacity < 0) throw new IllegalArgumentException("GPU queue capacity must not be negative");
        int workers = Integer.getInteger("td20.reconstructionGpuWorkers",
                Math.addExact(Math.multiplyExact(2, layout.totalSlots()), capacity));
        if (workers < 1) throw new IllegalArgumentException("Reconstruction GPU workers must be positive");
        return workers;
    }

    public static void process(DiskStateSet states, Path spool, int cpuWorkers, int gpuWorkers,
                               Function<List<DiskStateSet.State>, boolean[]> lookup,
                               BiPredicate<CyclicTargetPair, Integer> cpu,
                               BiPredicate<CyclicTargetPair, Integer> gpu,
                               Consumer<DiskStateSet.State> residual) {
        process(states, spool, cpuWorkers, gpuWorkers, lookup, cpu, gpu, residual, () -> { });
    }

    public static void process(DiskStateSet states, Path spool, int cpuWorkers, int gpuWorkers,
                               Function<List<DiskStateSet.State>, boolean[]> lookup,
                               BiPredicate<CyclicTargetPair, Integer> cpu,
                               BiPredicate<CyclicTargetPair, Integer> gpu,
                               Consumer<DiskStateSet.State> residual, Runnable gpuDrained) {
        processPrimitive(states, spool, cpuWorkers, gpuWorkers, lookup,
                (pair, budget) -> cpu.test(LegacyPairs.toLegacy(pair), budget),
                (pair, budget) -> gpu.test(LegacyPairs.toLegacy(pair), budget), residual, gpuDrained);
    }

    public static void processPrimitive(DiskStateSet states, Path spool, int cpuWorkers, int gpuWorkers,
                               Function<List<DiskStateSet.State>, boolean[]> lookup,
                               BiPredicate<PrimitivePair, Integer> cpu,
                               BiPredicate<PrimitivePair, Integer> gpu,
                               Consumer<DiskStateSet.State> residual) {
        processPrimitive(states, spool, cpuWorkers, gpuWorkers, lookup, cpu, gpu, residual, () -> { });
    }

    public static void processPrimitive(DiskStateSet states, Path spool, int cpuWorkers, int gpuWorkers,
                               Function<List<DiskStateSet.State>, boolean[]> lookup,
                               BiPredicate<PrimitivePair, Integer> cpu,
                               BiPredicate<PrimitivePair, Integer> gpu,
                               Consumer<DiskStateSet.State> residual, Runnable gpuDrained) {
        processPrimitive(states,spool,cpuWorkers,gpuWorkers,lookup,cpu,gpu,residual,gpuDrained,() -> "");
    }

    public static void processPrimitive(DiskStateSet states, Path spool, int cpuWorkers, int gpuWorkers,
                               Function<List<DiskStateSet.State>, boolean[]> lookup,
                               BiPredicate<PrimitivePair, Integer> cpu,
                               BiPredicate<PrimitivePair, Integer> gpu,
                               Consumer<DiskStateSet.State> residual, Runnable gpuDrained, Supplier<String> details) {
        var total = new AtomicLong(states.prepareScan());
        int inverseWorkers = Integer.getInteger("td20.reconstructionInverseWorkers",
                Math.min(cpuWorkers, Runtime.getRuntime().availableProcessors()));
        if (inverseWorkers < 1) throw new IllegalArgumentException("Inverse workers must be positive");
        var cached = new LongAdder(); var cpuClosed = new LongAdder();
        var gpuClosed = new LongAdder(); var inverseCompleted = new LongAdder();
        try (var queue = new DiskStateQueue(spool);
             var inverseQueue = new DiskStateQueue(spool.resolveSibling(spool.getFileName() + ".inverse.queue"));
             var reporter = Executors.newSingleThreadScheduledExecutor(
                     Thread.ofPlatform().daemon(true).name("reconstruction-progress").factory())) {
            Runnable report = () -> {
                long cachedCount = cached.sum(), cpuCount = cpuClosed.sum(), gpuCount = gpuClosed.sum();
                long inverseCount = inverseCompleted.sum(), submitted = queue.appended(), misses = inverseQueue.appended();
                long processed = cachedCount + cpuCount + gpuCount + inverseCount;
                long expected = total.get();
                String percent = expected < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f", expected == 0 ? 100.0 : 100.0 * processed / expected);
                String extra=details.get();
                log.info("Inverse reconstruction {} search/inverse: processed={}/{} ({}%), remaining={}, cached={}, cpuClosed={}, "
                                + "cpuRemaining={}, gpuSubmitted={}, gpuClosed={}, gpuMisses={}, gpuRemaining={}, inverseCompleted={}, inverseRemaining={}{}",
                        spool.getFileName(), processed, expected < 0 ? "unknown" : expected, percent,
                        expected < 0 ? "unknown" : expected - processed, cachedCount, cpuCount,
                        expected < 0 ? "unknown" : expected - cachedCount - cpuCount - submitted, submitted, gpuCount, misses,
                        submitted - gpuCount - misses, inverseCount, misses - inverseCount,
                        extra.isEmpty()?"":", "+extra);
            };
            log.info("Reconstruction pipeline {}: CPU workers={}, GPU workers={}, inverse workers={}, disk queue={}",
                    spool.getFileName(), cpuWorkers, gpuWorkers, inverseWorkers, spool);
            report.run();
            reporter.scheduleWithFixedDelay(report, 10, 10, TimeUnit.SECONDS);
            try (var inverseConsumers = new BoundedTasks(inverseWorkers, "reconstruction-inverse-")) {
                for (int i = 0; i < inverseWorkers; i++) inverseConsumers.submit(() -> {
                    try {
                        for (DiskStateSet.State state; (state = inverseQueue.take()) != null;) {
                            queue.check();
                            residual.accept(state);
                            inverseCompleted.increment();
                        }
                    } catch (RuntimeException | Error error) {
                        queue.abort(error); inverseQueue.abort(error); throw error;
                    }
                });
                try {
                    try (var consumers = new BoundedTasks(gpuWorkers, "reconstruction-gpu-")) {
                        for (int i = 0; i < gpuWorkers; i++) consumers.submit(() -> {
                            try {
                                for (DiskStateSet.State state; (state = queue.take()) != null;) {
                                    boolean closed = gpu.test(state.primitivePair(), state.maximumMoves());
                                    queue.check();
                                    if (closed) gpuClosed.increment(); else inverseQueue.add(state);
                                }
                            } catch (RuntimeException | Error error) {
                                queue.abort(error); inverseQueue.abort(error); throw error;
                            }
                        });
                        try {
                            states.forEachConcurrently(cpuWorkers, lookup, state -> {
                                queue.check(); cached.increment();
                            }, state -> {
                                queue.check();
                                if (cpu.test(state.primitivePair(), state.maximumMoves())) cpuClosed.increment();
                                else queue.add(state);
                            });
                            total.set(states.knownSize());
                            if (total.get() < 0) throw new IllegalStateException("Missing exhaustive source inventory");
                            queue.finish();
                        } catch (RuntimeException | Error error) {
                            queue.abort(error); inverseQueue.abort(error); throw error;
                        }
                    }
                    queue.check();
                    gpuDrained.run();
                    inverseQueue.finish();
                } catch (RuntimeException | Error error) {
                    queue.abort(error); inverseQueue.abort(error); throw error;
                }
            }
            queue.check(); inverseQueue.check();
            if (cached.sum() + cpuClosed.sum() + queue.appended() != total.get()
                    || gpuClosed.sum() + inverseQueue.appended() != queue.appended()
                    || inverseCompleted.sum() != inverseQueue.appended())
                throw new IllegalStateException("Incomplete reconstruction pipeline coverage");
            report.run();
        }
    }
}
