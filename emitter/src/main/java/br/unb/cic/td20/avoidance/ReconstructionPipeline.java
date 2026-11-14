package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.runtime.BoundedTasks;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CPU work never waits for GPU capacity. Only CPU misses enter the disk-backed handoff. */
public final class ReconstructionPipeline {
    private static final Logger log = LoggerFactory.getLogger(ReconstructionPipeline.class);
    private ReconstructionPipeline() { }

    public static int configuredGpuWorkers() {
        var layout = new GpuDeviceLayout(GpuDeviceLayout.parseDevices(
                System.getProperty("td20.gpuDevices", "0")), Integer.getInteger("td20.gpuSlots", 7));
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
        long total = states.seal();
        var cached = new LongAdder(); var cpuClosed = new LongAdder();
        var gpuClosed = new LongAdder(); var unresolved = new LongAdder();
        try (var queue = new DiskStateQueue(spool);
             var reporter = Executors.newSingleThreadScheduledExecutor(
                     Thread.ofPlatform().daemon(true).name("reconstruction-progress").factory())) {
            Runnable report = () -> log.info("Reconstruction pipeline {}: total={}, cached={}, cpuClosed={}, "
                            + "queued={}, gpuClosed={}, gpuMisses={}, backlog={}", spool.getFileName(), total,
                    cached.sum(), cpuClosed.sum(), queue.appended(), gpuClosed.sum(), unresolved.sum(),
                    queue.appended() - gpuClosed.sum() - unresolved.sum());
            log.info("Reconstruction pipeline {}: CPU workers={}, GPU workers={}, disk queue={}",
                    spool.getFileName(), cpuWorkers, gpuWorkers, spool);
            reporter.scheduleWithFixedDelay(report, 10, 10, TimeUnit.SECONDS);
            try (var consumers = new BoundedTasks(gpuWorkers, "reconstruction-gpu-")) {
                for (int i = 0; i < gpuWorkers; i++) consumers.submit(() -> {
                    try {
                        for (DiskStateSet.State state; (state = queue.take()) != null;) {
                            boolean closed = gpu.test(state.pair(), state.maximumMoves());
                            queue.check();
                            if (closed) gpuClosed.increment();
                            else { residual.accept(state); unresolved.increment(); }
                        }
                    } catch (RuntimeException | Error error) { queue.abort(error); throw error; }
                });
                try {
                    states.forEachConcurrently(cpuWorkers, lookup, state -> {
                        queue.check(); cached.increment();
                    }, state -> {
                        queue.check();
                        if (cpu.test(state.pair(), state.maximumMoves())) cpuClosed.increment();
                        else queue.add(state);
                    });
                    queue.finish();
                } catch (RuntimeException | Error error) { queue.abort(error); throw error; }
            }
            queue.check();
            if (cached.sum() + cpuClosed.sum() + queue.appended() != total
                    || gpuClosed.sum() + unresolved.sum() != queue.appended())
                throw new IllegalStateException("Incomplete reconstruction pipeline coverage");
            report.run();
        }
    }
}
