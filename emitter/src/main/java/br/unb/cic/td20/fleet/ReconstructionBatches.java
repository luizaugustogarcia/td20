package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.*;
import br.unb.cic.td20.joint.ErikssonRoute;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** First inverse wave on CPU, then deterministic disjoint batches of sorted after-two keys. */
final class ReconstructionBatches {
    private ReconstructionBatches() { }
    static List<FleetTask> prepare(String run, Path directory, List<Path> jobs, int batchSize) throws IOException {
        return prepare(run,directory,jobs,batchSize,message -> { });
    }
    static List<FleetTask> prepare(String run, Path directory, List<Path> jobs, int batchSize,
                                   java.util.function.Consumer<String> progress) throws IOException {
        Files.createDirectory(directory);
        long endpointCount;
        try (var endpoints = new DiskStateSet(directory.resolve("endpoints"))) {
            for (Path job : jobs) {
                var task = FleetTask.read(job);
                long expected = task.receiptCounts(job).endpointResiduals();
                if (!task.phase().equals("endpoints")) continue;
                try (var input = DiskStateSet.openSealedForScan(job.resolve("work/endpoint-residuals"), expected)) {
                    input.forEachConcurrently(Runtime.getRuntime().availableProcessors(), state -> {
                        requireEndpoint(state); endpoints.add(state.pair(), state.maximumMoves());
                    });
                }
            }
            endpointCount = endpoints.seal();
            progress.accept("Validated residual endpoint union: " + endpointCount + " classes");
            try (var afterTwo = new DiskStateSet(directory.resolve("after-two"))) {
                var completed = new java.util.concurrent.atomic.AtomicLong();
                endpoints.forEachConcurrently(Runtime.getRuntime().availableProcessors(), state -> afterTwo.addGenerated(
                        ErikssonRoute.ROOT_BUDGET - 2, output -> {
                            ErikssonInverseReconstruction.preimages(state.pair(), 2, output);
                            progress.accept("First inverse wave endpoint seeds processed=" + completed.incrementAndGet() + "/" + endpointCount);
                        }));
                long total = afterTwo.seal();
                progress.accept("First inverse wave unique after-two classes=" + total + "; splitting batches");
                var tasks = split(run, directory, afterTwo, endpointCount, batchSize);
                long assigned = 0;
                var lines = new ArrayList<>(List.of("td20-fleet-batches-v1", "route=" + ErikssonRoute.ID,
                        "run=" + run, "endpoints=" + endpointCount, "afterTwo=" + total, "batches=" + tasks.size()));
                for (var task : tasks) { assigned = Math.addExact(assigned, task.inputCount()); lines.add(task.id() + "=" + task.inputCount()); }
                if (assigned != total) throw new IllegalStateException("Incomplete reconstruction batch assignment");
                FleetFiles.write(directory.resolve("batches.txt"), lines);
                progress.accept("Counted reconstruction assignment complete: classes=" + assigned + ", batches=" + tasks.size());
                return tasks;
            }
        }
    }
    static List<FleetTask> read(String run, Path directory) throws IOException {
        var lines = Files.readAllLines(directory.resolve("batches.txt"));
        if (lines.size() < 6 || !lines.getFirst().equals("td20-fleet-batches-v1")
                || !lines.get(1).equals("route=" + ErikssonRoute.ID) || !lines.get(2).equals("run=" + run))
            throw new IllegalArgumentException("Incompatible reconstruction batch inventory");
        long endpoints = Long.parseLong(FleetFiles.field(lines,3,"endpoints"));
        long total = Long.parseLong(FleetFiles.field(lines,4,"afterTwo"));
        int count = Integer.parseInt(FleetFiles.field(lines,5,"batches"));
        if (endpoints < 0 || total < 0 || count < 0 || lines.size() != 6L + count)
            throw new IllegalArgumentException("Incomplete reconstruction batch inventory");
        var tasks = new ArrayList<FleetTask>(); long assigned = 0;
        for (int i = 0; i < count; i++) {
            String id = id(i); long size = Long.parseLong(FleetFiles.field(lines,i + 6,id));
            var task = FleetTask.read(directory.resolve(id));
            if (!task.equals(new FleetTask(run,id,"avoidance",1,1,size))) throw new IllegalArgumentException("Batch scope mismatch");
            var checkpoint = ErikssonCheckpoint.read(directory.resolve(id).resolve("input"));
            if (checkpoint.stage() != ErikssonCheckpoint.Stage.AFTER_TWO || checkpoint.endpoints() != endpoints || checkpoint.afterTwo() != size)
                throw new IllegalArgumentException("Incompatible batch checkpoint");
            tasks.add(task); assigned = Math.addExact(assigned, size);
        }
        if (assigned != total) throw new IllegalArgumentException("Missing reconstruction batch keys");
        return List.copyOf(tasks);
    }
    static List<FleetTask> split(String run, Path directory, DiskStateSet source, long endpoints, int batchSize) throws IOException {
        if (batchSize < 1) throw new IllegalArgumentException("Positive reconstruction batch size required");
        var tasks = new ArrayList<FleetTask>();
        class Sink implements AutoCloseable {
            DiskStateSet batch; Path job; long size;
            void accept(DiskStateSet.State state) {
                if (state.pair().getBeta().size() != ErikssonRoute.ROOT_SYMBOLS - 3 || state.maximumMoves() != ErikssonRoute.ROOT_BUDGET - 2)
                    throw new IllegalArgumentException("Wrong domain or budget in reconstruction batch");
                try {
                    if (batch == null) {
                        job = Files.createDirectory(directory.resolve(id(tasks.size())));
                        Files.createDirectory(job.resolve("input")); batch = new DiskStateSet(job.resolve("input/after-two")); size = 0;
                    }
                    batch.add(state.pair(), state.maximumMoves()); size++;
                    if (size == batchSize) finish();
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            }
            void finish() throws IOException {
                if (batch == null) return;
                if (batch.seal() != size) throw new IllegalStateException("Duplicate or missing batch keys");
                batch.close(); batch = null;
                new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,endpoints,size,-1,-1).write(job.resolve("input"));
                var task = new FleetTask(run,job.getFileName().toString(),"avoidance",1,1,size); task.write(job); tasks.add(task);
            }
            public void close() { if (batch != null) batch.close(); }
        }
        try (var sink = new Sink()) { source.forEach(sink::accept); sink.finish(); }
        return List.copyOf(tasks);
    }
    private static void requireEndpoint(DiskStateSet.State state) {
        if (state.pair().getBeta().size() != ErikssonRoute.ENDPOINT_SYMBOLS || state.maximumMoves() != ErikssonRoute.ENDPOINT_BUDGET)
            throw new IllegalArgumentException("Wrong endpoint residual domain or budget");
    }
    private static String id(int index) { return String.format(Locale.ROOT, "reconstruction-%06d", index); }
}
