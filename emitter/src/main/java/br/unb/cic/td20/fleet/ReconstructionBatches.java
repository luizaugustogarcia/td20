package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.*;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.runtime.BoundedTasks;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** First inverse wave on CPU, then deterministic disjoint batches of sorted after-two keys. */
final class ReconstructionBatches {
    private ReconstructionBatches() { }
    record Wave(Path directory, long endpoints, long afterTwo) { }

    static Wave prepareWave(Path directory, List<Path> jobs, java.util.function.Consumer<String> progress) throws IOException {
        Files.createDirectories(directory);
        Path waves = directory.resolve("waves");
        if (Files.exists(waves)) {
            var checkpoint = ErikssonCheckpoint.read(waves);
            if (checkpoint.stage() != ErikssonCheckpoint.Stage.AFTER_TWO) throw new IllegalArgumentException("Wrong preparation checkpoint stage");
            return new Wave(waves,checkpoint.endpoints(),checkpoint.afterTwo());
        }
        Path staging = Files.createDirectory(directory.resolve(".waves-" + UUID.randomUUID() + ".partial"));
        long endpointCount, total;
        try (var endpoints = new DiskStateSet(staging.resolve("endpoints"))) {
            for (Path job : jobs) {
                var task = FleetTask.read(job);
                long expected = task.receiptCounts(job).endpointResiduals();
                if (!task.phase().equals("endpoints")) continue;
                try (var input = DiskStateSet.openSealedForScan(job.resolve("work/endpoint-residuals"),expected)) {
                    input.forEachConcurrently(Runtime.getRuntime().availableProcessors(),state -> {
                        requireEndpoint(state); endpoints.add(state.pair(),state.maximumMoves());
                    });
                }
            }
            endpointCount = endpoints.seal();
            progress.accept("Validated residual endpoint union: " + endpointCount + " classes");
            try (var afterTwo = new DiskStateSet(staging.resolve("after-two"))) {
                endpoints.forEachConcurrently(Runtime.getRuntime().availableProcessors(),state -> afterTwo.addGenerated(
                        ErikssonRoute.ROOT_BUDGET - 2,output -> ErikssonInverseReconstruction.preimages(state.pair(),2,output)));
                total = afterTwo.seal();
            }
        }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,endpointCount,total,-1,-1).write(staging);
        Files.move(staging,waves,StandardCopyOption.ATOMIC_MOVE);
        progress.accept("First inverse wave unique after-two classes=" + total + "; publishing batches while servers compute");
        return new Wave(waves,endpointCount,total);
    }

    static List<FleetTask> describe(String run,long total,int batchSize) {
        if (total < 0 || batchSize < 1) throw new IllegalArgumentException("Invalid batch inventory");
        int count = Math.toIntExact(Math.ceilDiv(total,batchSize));
        var tasks = new ArrayList<FleetTask>(count);
        for (int i=0;i<count;i++) tasks.add(new FleetTask(run,id(i),"avoidance",1,1,Math.min(batchSize,total - (long)i * batchSize)));
        return List.copyOf(tasks);
    }

    static void publish(String run,Path directory,Wave wave,int batchSize,java.util.function.Consumer<FleetTask> ready) throws IOException {
        List<FleetTask> tasks;
        try (var source = DiskStateSet.openSealedForScan(wave.directory().resolve("after-two"),wave.afterTwo())) {
            tasks = split(run,directory,source,wave.endpoints(),batchSize,ready);
        }
        if (!tasks.equals(describe(run,wave.afterTwo(),batchSize))) throw new IllegalStateException("Incomplete reconstruction assignment");
        var lines = new ArrayList<>(List.of("td20-fleet-batches-v1","route="+ErikssonRoute.ID,"run="+run,
                "endpoints="+wave.endpoints(),"afterTwo="+wave.afterTwo(),"batches="+tasks.size()));
        tasks.forEach(task -> lines.add(task.id()+"="+task.inputCount()));
        FleetFiles.write(directory.resolve("batches.txt"),lines);
    }
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
        return split(run,directory,source,endpoints,batchSize,task -> { });
    }
    static List<FleetTask> split(String run, Path directory, DiskStateSet source, long endpoints, int batchSize,
                                  java.util.function.Consumer<FleetTask> ready) throws IOException {
        if (batchSize < 1) throw new IllegalArgumentException("Positive reconstruction batch size required");
        var tasks = new ArrayList<FleetTask>();
        int parallelism = Integer.getInteger("td20.fleetBatchWorkers",Runtime.getRuntime().availableProcessors());
        if (parallelism < 1) throw new IllegalArgumentException("Positive batch worker count required");
        try (var workers = new BoundedTasks(parallelism,"fleet-batch-")) {
        class Sink {
            ArrayList<DiskStateSet.State> states = new ArrayList<>();
            void accept(DiskStateSet.State state) {
                if (state.pair().getBeta().size() != ErikssonRoute.ROOT_SYMBOLS - 3 || state.maximumMoves() != ErikssonRoute.ROOT_BUDGET - 2)
                    throw new IllegalArgumentException("Wrong domain or budget in reconstruction batch");
                workers.check(); states.add(state); if (states.size() == batchSize) finish();
            }
            void finish() {
                if (states.isEmpty()) return;
                var batch = List.copyOf(states); states.clear();
                var task = new FleetTask(run,id(tasks.size()),"avoidance",1,1,batch.size()); tasks.add(task);
                workers.submit(() -> {
                    try { publishBatch(directory,task,endpoints,batch); ready.accept(task); }
                    catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                });
            }
        }
        var sink = new Sink(); source.forEach(sink::accept); sink.finish();
        }
        return List.copyOf(tasks);
    }
    private static void publishBatch(Path directory,FleetTask task,long endpoints,List<DiskStateSet.State> states) throws IOException {
        Path job = directory.resolve(task.id());
        if (Files.exists(job) && !Files.isRegularFile(job.resolve("task.txt")))
            Files.move(job,directory.resolve(".unpublished-"+task.id()+"-"+UUID.randomUUID()),StandardCopyOption.ATOMIC_MOVE);
        if (Files.exists(job)) {
            if (!task.equals(FleetTask.read(job)) || !new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,endpoints,states.size(),-1,-1)
                    .equals(ErikssonCheckpoint.read(job.resolve("input")))) throw new IllegalArgumentException("Existing batch scope differs");
            var index = new java.util.concurrent.atomic.AtomicInteger();
            try (var input = DiskStateSet.openSealedForScan(job.resolve("input/after-two"),states.size())) {
                input.forEach(state -> {
                    int i = index.getAndIncrement();
                    if (i >= states.size() || state.maximumMoves() != states.get(i).maximumMoves()
                            || !Arrays.equals(CertificateCodec.keyFor(state.pair()),CertificateCodec.keyFor(states.get(i).pair())))
                        throw new IllegalStateException("Existing batch differs from its regenerated sorted slice");
                });
            }
            if (index.get() != states.size()) throw new IllegalStateException("Missing existing batch keys");
            return;
        }
        Path staging = Files.createDirectory(directory.resolve("."+task.id()+"-"+UUID.randomUUID()+".partial"));
        Path input = Files.createDirectory(staging.resolve("input"));
        DiskStateSet.writeSealedBatch(input.resolve("after-two"),states);
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,endpoints,states.size(),-1,-1).write(input);
        task.write(staging);
        Files.move(staging,job,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void requireEndpoint(DiskStateSet.State state) {
        if (state.pair().getBeta().size() != ErikssonRoute.ENDPOINT_SYMBOLS || state.maximumMoves() != ErikssonRoute.ENDPOINT_BUDGET)
            throw new IllegalArgumentException("Wrong endpoint residual domain or budget");
    }
    private static String id(int index) { return String.format(Locale.ROOT, "reconstruction-%06d", index); }
}
