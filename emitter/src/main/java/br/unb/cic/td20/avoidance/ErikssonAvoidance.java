package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Three inverse waves, both gain branches, both endpoints, and disk-backed deduplication.
 */
public final class ErikssonAvoidance {

    private ErikssonAvoidance() {
    }

    public record Result(long endpoints, long afterTwo, long afterOne, long roots, long unresolved) {
    }

    public static Result resolve(final DiskStateSet endpoints,
            final Path work,
            final BiPredicate<CyclicTargetPair, Integer> discharge,
            final Function<List<DiskStateSet.State>, boolean[]> lookup) {
        return resolve(endpoints, work, discharge, (pair, budget) -> false, lookup);
    }

    public static Result resolve(final DiskStateSet endpoints,
            final Path work,
            final BiPredicate<CyclicTargetPair, Integer> cpu,
            final BiPredicate<CyclicTargetPair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup) {
        return resolve(endpoints, work, cpu, gpu, lookup, gpu);
    }

    public static Result resolve(final DiskStateSet endpoints,
            final Path work,
            final BiPredicate<CyclicTargetPair, Integer> cpu,
            final BiPredicate<CyclicTargetPair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<CyclicTargetPair, Integer> prefetchGpu) {
        return resolvePrimitive(endpoints, work, adapt(cpu), adapt(gpu), lookup, adapt(prefetchGpu));
    }

    public static Result resolvePrimitive(final DiskStateSet endpoints,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> prefetchGpu) {
        return resolvePrimitive(endpoints, work, cpu, gpu, lookup, prefetchGpu, () -> {
        });
    }

    public static Result resolvePrimitive(final DiskStateSet endpoints,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> prefetchGpu,
            final Runnable persistenceBarrier) {
        java.util.Objects.requireNonNull(persistenceBarrier);

        final long count = endpoints.seal();
        val completed = new LongAdder();

        try (val afterTwo = new DiskStateSet(work.resolve("after-two"))) {
            try (val progress = new ReconstructionProgress("Inverse reconstruction after-two generation (endpoint classes)", count, completed::sum)) {
                endpoints.forEachConcurrently(workers(),
                        state -> {
                            require(state, 0);
                            afterTwo.addInverseGenerated(state.primitivePair(), ErikssonRoute.ROOT_BUDGET - 2, 2);
                            completed.increment();
                        });
            }

            val checkpoint = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO, count, afterTwo.seal(), -1, -1);
            checkpoint.write(work);

            return afterTwo(checkpoint, afterTwo, work, cpu, gpu, lookup, prefetchGpu, persistenceBarrier);
        }
    }

    public static Result resume(final Path source,
            final Path work,
            final BiPredicate<CyclicTargetPair, Integer> discharge,
            final Function<List<DiskStateSet.State>, boolean[]> lookup) {
        return resume(source, work, discharge, (pair, budget) -> false, lookup);
    }

    public static Result resume(final Path source,
            final Path work,
            final BiPredicate<CyclicTargetPair, Integer> cpu,
            final BiPredicate<CyclicTargetPair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup) {
        return resume(source, work, cpu, gpu, lookup, gpu);
    }

    public static Result resume(final Path source,
            final Path work,
            final BiPredicate<CyclicTargetPair, Integer> cpu,
            final BiPredicate<CyclicTargetPair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<CyclicTargetPair, Integer> prefetchGpu) {
        return resumePrimitive(source, work, adapt(cpu), adapt(gpu), lookup, adapt(prefetchGpu));
    }

    public static Result resumePrimitive(final Path source,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> prefetchGpu) {
        return resumePrimitive(source, work, cpu, gpu, lookup, prefetchGpu, () -> {
        });
    }

    public static Result resumePrimitive(final Path source,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> prefetchGpu,
            final Runnable persistenceBarrier) {
        java.util.Objects.requireNonNull(persistenceBarrier);

        val checkpoint = ErikssonCheckpoint.read(source);
        final String name = checkpoint.stage() == ErikssonCheckpoint.Stage.AFTER_TWO ? "after-two" : checkpoint.stage() == ErikssonCheckpoint.Stage.AFTER_ONE ? "after-one" : "roots";
        final long count = checkpoint.stage() == ErikssonCheckpoint.Stage.AFTER_TWO ? checkpoint.afterTwo() : checkpoint.stage() == ErikssonCheckpoint.Stage.AFTER_ONE ? checkpoint.afterOne() : checkpoint.roots();

        // Every resumed wave validates its inventory during mandatory processing.
        try (val states = checkpoint.afterOneCountPending() ? DiskStateSet.openCompletedForScan(source.resolve(name)) : DiskStateSet.openSealedForScan(source.resolve(name), count)) {
            return switch(checkpoint.stage()) {
                case AFTER_TWO ->
                    afterTwo(checkpoint, states, work, cpu, gpu, lookup, prefetchGpu, persistenceBarrier);
                case AFTER_ONE ->
                    afterOne(checkpoint, states, source, work, cpu, gpu, lookup, prefetchGpu, persistenceBarrier);
                case ROOTS ->
                    roots(checkpoint, states, work, cpu, gpu, lookup);
            };
        }
    }

    private static Result afterTwo(final ErikssonCheckpoint checkpoint,
            final DiskStateSet states,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> prefetchGpu,
            final Runnable persistenceBarrier) {
        try (val afterOne = new DiskStateSet(work.resolve("after-one"))) {
            final Consumer<DiskStateSet.State> expand = state -> {
                require(state, 1);
                afterOne.addInverseGenerated(state.primitivePair(), ErikssonRoute.ROOT_BUDGET - 1, 2, 3);
            };
            if (checkpoint.mode() == ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION) {
                expandOnly(states, "after-two residuals", checkpoint.afterTwo(), expand, () -> writeProgress(afterOne));
            } else {
                ReconstructionPipeline.processPrimitive(states,
                        work.resolve("after-two-search.queue"),
                        workers(),
                        ReconstructionPipeline.configuredGpuWorkers(),
                        checked(lookup, 1),
                        cpu,
                        gpu,
                        expand,
                        () -> {
                        },
                        () -> writeProgress(afterOne));
            }

            // Results that discharge upstream sources must be durable before
            // a completed downstream subset becomes a resumable checkpoint.
            persistenceBarrier.run();

            // RocksDB already deduplicates canonical keys. Count the completed
            // logical set without decoding or searching before the next wave.
            final long afterOneCount = afterOne.countKeys();
            val next = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,
                    checkpoint.endpoints(),
                    checkpoint.afterTwo(),
                    afterOneCount,
                    -1,
                    checkpoint.mode());
            next.write(work);

            return afterOne(next, afterOne, work, work, cpu, gpu, lookup, prefetchGpu, persistenceBarrier);
        }
    }

    private static Result afterOne(ErikssonCheckpoint checkpoint,
            final DiskStateSet states,
            final Path checkpointSource,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final BiPredicate<PrimitivePair, Integer> prefetchGpu,
            final Runnable persistenceBarrier) {
        checkpoint = countAfterOneIfPending(checkpoint, states, checkpointSource);

        val layout = new GpuDeviceLayout(GpuDeviceLayout.parseDevices(System.getProperty("td20.gpuDevices", "0")),
                Integer.getInteger("td20.gpuSlots", 8));

        try (val prefetch = RootPrefetch.primitive(layout.totalSlots(), cpu, prefetchGpu, checked(lookup, 3));
            val roots = new DiskStateSet(work.resolve("roots"), prefetch::publish)) {
            prefetch.activate();

            final Consumer<DiskStateSet.State> expand = state -> {
                require(state, 2);
                roots.addFinalInverseGenerated(state.primitivePair());
            };
            expandOnly(states,
                    "after-one family-filtered inverse states",
                    checkpoint.afterOne(),
                    expand,
                    () -> finalProgress(roots));

            final long afterOneCount = states.knownSize();
            if (afterOneCount != checkpoint.afterOne()) {
                throw new IllegalStateException("Final expansion did not process the counted intermediate inventory");
            }

            val counted = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,
                    checkpoint.endpoints(),
                    checkpoint.afterTwo(),
                    afterOneCount,
                    -1,
                    checkpoint.mode());
            roots.prepareScan();
            prefetch.finish();

            // Count while processing roots; publish a counted checkpoint only
            // after the exhaustive scan and all search workers have joined.
            val result = roots(counted, roots, work, cpu, gpu, lookup);
            persistenceBarrier.run();
            new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS,
                    checkpoint.endpoints(),
                    checkpoint.afterTwo(),
                    afterOneCount,
                    result.roots(),
                    checkpoint.mode(),
                    ErikssonCheckpoint.RootInventory.ELEMENTARY_RESIDUALS).write(work);

            return result;
        }
    }

    private static Result roots(final ErikssonCheckpoint checkpoint,
            final DiskStateSet states,
            final Path work,
            final BiPredicate<PrimitivePair, Integer> cpu,
            final BiPredicate<PrimitivePair, Integer> gpu,
            final Function<List<DiskStateSet.State>, boolean[]> lookup) {
        val unresolved = new AtomicLong();
        ReconstructionPipeline.processPrimitive(states,
                work.resolve("roots-search.queue"),
                workers(),
                ReconstructionPipeline.configuredGpuWorkers(),
                checked(lookup, 3),
                cpu,
                gpu,
                state -> {
                    require(state, 3);
                    unresolved.incrementAndGet();
                });

        return new Result(checkpoint.endpoints(), checkpoint.afterTwo(), checkpoint.afterOne(), states.knownSize(), unresolved.get());
    }

    private static int workers() {
        return DirectTwistedBraceletGenerator.configuredWorkerCount();
    }

    private static void expandOnly(final DiskStateSet states,
            final String name,
            final long expected,
            final Consumer<DiskStateSet.State> output,
            final Supplier<String> details) {
        val completed = new LongAdder();

        try (val progress = new ReconstructionProgress("Inverse expansion " + name, expected, completed::sum, details)) {
            states.forEachConcurrently(workers(), state -> {
                output.accept(state);
                completed.increment();
            });
        }
    }

    private static String writeProgress(final DiskStateSet output) {
        val writes = output.writeProgress();
        return "generatedKeyOccurrences=" + writes.generatedKeyOccurrences() + ", submittedKeyOccurrences=" + writes.submittedKeyOccurrences() + ", consumedKeyOccurrences=" + writes.consumedKeyOccurrences() + ", ingestedKeyOccurrences=" + writes.ingestedKeyOccurrences() + ", queuedKeyOccurrences=" + writes.queuedKeyOccurrences() + ", ingestedTables=" + writes.ingestedTables() + ", outputUniqueClasses=unknown until counted scan";
    }

    private static String finalProgress(final DiskStateSet output) {
        val proof = output.finalInverseProgress();
        return "rootInventory=ELEMENTARY_RESIDUALS, candidateSlots=" + proof.candidateSlots() + ", familyClosedSlots=" + proof.familyClosedSlots() + ", closedEndpointFamilies=" + proof.closedEndpointFamilies() + ", replayedParents=" + proof.replayedParents() + ", immediateClosedParents=" + proof.immediateClosedParents() + ", " + writeProgress(output);
    }

    private static ErikssonCheckpoint countAfterOneIfPending(final ErikssonCheckpoint checkpoint,
            final DiskStateSet states,
            final Path source) {
        if (!checkpoint.afterOneCountPending()) {
            return checkpoint;
        }

        final long count = states.countKeys();
        val counted = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,
                checkpoint.endpoints(),
                checkpoint.afterTwo(),
                count,
                -1,
                checkpoint.mode());
        counted.write(source);

        return counted;
    }

    /**
     * Upgrade a completed legacy wave by counting logical keys without inverse generation or search.
     */
    public static ErikssonCheckpoint countCompletedAfterOne(final Path source) {
        val checkpoint = ErikssonCheckpoint.read(source);
        if (checkpoint.stage() != ErikssonCheckpoint.Stage.AFTER_ONE) {
            throw new IllegalArgumentException("Counting requires a completed AFTER_ONE checkpoint");
        }

        try (val states = checkpoint.afterOneCountPending() ? DiskStateSet.openCompletedForScan(source.resolve("after-one")) : DiskStateSet.openSealedForScan(source.resolve("after-one"), checkpoint.afterOne())) {
            final long count = states.countKeys();
            val counted = new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,
                    checkpoint.endpoints(),
                    checkpoint.afterTwo(),
                    count,
                    -1,
                    checkpoint.mode());
            counted.write(source);

            return counted;
        }
    }

    private static Function<List<DiskStateSet.State>, boolean[]> checked(final Function<List<DiskStateSet.State>, boolean[]> lookup,
            final int stage) {
        return batch -> {
            for (val state : batch) {
                require(state, stage);
            }

            return lookup == null ? new boolean[batch.size()] : lookup.apply(batch);
        };
    }

    private static void require(final DiskStateSet.State state, final int stage) {
        final int n = state.primitivePair().size(), budget = ErikssonRoute.ROOT_BUDGET;
        final boolean valid = switch(stage) {
            case 0 ->
                n == ErikssonRoute.ENDPOINT_SYMBOLS && state.maximumMoves() == budget - 3;
            case 1 ->
                n == ErikssonRoute.ROOT_SYMBOLS - 3 && state.maximumMoves() == budget - 2;
            case 2 ->
                (n == ErikssonRoute.ROOT_SYMBOLS - 1 || n == ErikssonRoute.ROOT_SYMBOLS) && state.maximumMoves() == budget - 1;
            case 3 ->
                n == ErikssonRoute.ROOT_SYMBOLS && state.maximumMoves() == budget;
            default ->
                false;
        };
        if (!valid) {
            throw new IllegalStateException("Wrong domain or budget in Eriksson inverse stage " + stage);
        }
    }

    private static BiPredicate<PrimitivePair, Integer> adapt(final BiPredicate<CyclicTargetPair, Integer> legacy) {
        return (pair, budget) -> legacy.test(LegacyPairs.toLegacy(pair), budget);
    }
}
