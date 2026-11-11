package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.model.CyclicTargetPair;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disk-backed three-wave reconstruction from regenerated endpoint witness misses. */
public final class JointAvoidance {
    private static final Logger log = LoggerFactory.getLogger(JointAvoidance.class);
    private JointAvoidance() { }

    public record Result(long endpoints, long intermediates, long propagatedIntermediates,
                         long middles, long propagatedMiddles, long roots, long unresolved) { }

    public static Result resolve(DiskStateSet endpoints, Path work,
                                 Predicate<CyclicTargetPair> intermediateClosure,
                                 Predicate<CyclicTargetPair> middleClosure,
                                 Predicate<CyclicTargetPair> rootClosure) {
        return resolve(endpoints, work, intermediateClosure, middleClosure, rootClosure, null);
    }

    public static Result resolve(DiskStateSet endpoints, Path work,
                                 Predicate<CyclicTargetPair> intermediateClosure,
                                 Predicate<CyclicTargetPair> middleClosure,
                                 Predicate<CyclicTargetPair> rootClosure,
                                 Function<List<DiskStateSet.State>, boolean[]> lookup) {
        final int workers = DirectTwistedBraceletGenerator.configuredWorkerCount();
        try (var intermediates = new DiskStateSet(work.resolve("intermediates"));
             var progress = new Progress()) {
            final long endpointCount = endpoints.seal();
            final var generated = new AtomicLong();
            progress.phase(() -> "reversing third moves: endpoints=" + generated.get() + "/" + endpointCount);
            endpoints.forEachConcurrently(workers, state -> {
                requireState(state, 8, 16);
                JointInverseReconstruction.intermediates(state.pair(), pair -> intermediates.add(pair, 9));
                generated.incrementAndGet();
            });
            final long intermediateCount = intermediates.seal();
            new ReconstructionCheckpoint(endpointCount, intermediateCount).write(work);
            log.info("Third-move reconstruction: endpoints={}, intermediateClasses={}, workers={}",
                    endpointCount, intermediateCount, workers);
            return searchIntermediates(endpointCount, intermediates, work, intermediateClosure, middleClosure,
                    rootClosure, lookup, workers, progress);
        }
    }

    /** Resumes the latest complete sealed inverse wave and replays available results. */
    public static Result resume(Path checkpointWork, Path work,
                                Predicate<CyclicTargetPair> intermediateClosure,
                                Predicate<CyclicTargetPair> middleClosure,
                                Predicate<CyclicTargetPair> rootClosure) {
        return resume(checkpointWork, work, intermediateClosure, middleClosure, rootClosure, null);
    }

    public static Result resume(Path checkpointWork, Path work,
                                Predicate<CyclicTargetPair> intermediateClosure,
                                Predicate<CyclicTargetPair> middleClosure,
                                Predicate<CyclicTargetPair> rootClosure,
                                Function<List<DiskStateSet.State>, boolean[]> lookup) {
        final int workers = DirectTwistedBraceletGenerator.configuredWorkerCount();
        if (RootReconstructionCheckpoint.exists(checkpointWork)) {
            final var checkpoint = RootReconstructionCheckpoint.read(checkpointWork);
            try (var roots = DiskStateSet.openSealed(checkpointWork.resolve("roots"), checkpoint.roots());
                 var progress = new Progress()) {
                log.info("Resuming sealed root reconstruction: rootClasses={}, workers={}, lookupBatch={}, source={}",
                        checkpoint.roots(), workers, DiskStateSet.lookupBatchSize(), checkpointWork);
                return searchRoots(checkpoint, roots, rootClosure, lookup, workers, progress);
            }
        }
        if (MiddleReconstructionCheckpoint.exists(checkpointWork)) {
            final var checkpoint = MiddleReconstructionCheckpoint.read(checkpointWork);
            try (var middles = DiskStateSet.openSealed(checkpointWork.resolve("middles"), checkpoint.middles());
                 var progress = new Progress()) {
                log.info("Resuming sealed middle reconstruction: middleClasses={}, workers={}, lookupBatch={}, source={}",
                        checkpoint.middles(), workers, DiskStateSet.lookupBatchSize(), checkpointWork);
                return searchMiddles(checkpoint, middles, work, middleClosure, rootClosure, lookup, workers, progress);
            }
        }
        final var checkpoint = ReconstructionCheckpoint.read(checkpointWork);
        try (var endpoints = DiskStateSet.openSealed(checkpointWork.resolve("endpoint-residuals"), checkpoint.endpoints());
             var intermediates = DiskStateSet.openSealed(checkpointWork.resolve("intermediates"), checkpoint.intermediates());
             var progress = new Progress()) {
            endpoints.forEach(state -> requireState(state, 8, 16));
            log.info("Resuming third-move reconstruction: endpoints={}, intermediateClasses={}, workers={}, lookupBatch={}, source={}",
                    checkpoint.endpoints(), checkpoint.intermediates(), workers, DiskStateSet.lookupBatchSize(), checkpointWork);
            return searchIntermediates(checkpoint.endpoints(), intermediates, work, intermediateClosure, middleClosure,
                    rootClosure, lookup, workers, progress);
        }
    }

    private static Result searchIntermediates(long endpointCount, DiskStateSet intermediates, Path work,
                                              Predicate<CyclicTargetPair> intermediateClosure,
                                              Predicate<CyclicTargetPair> middleClosure,
                                              Predicate<CyclicTargetPair> rootClosure,
                                              Function<List<DiskStateSet.State>, boolean[]> lookup,
                                              int workers, Progress progress) {
        try (var middles = new DiskStateSet(work.resolve("middles"))) {
            final long intermediateCount = intermediates.seal();
            final var completed = new AtomicLong();
            final var cached = new AtomicLong();
            final var propagated = new AtomicLong();
            final var intermediateLookup = checkedLookup(lookup, 9, 18, 19);
            progress.phase(() -> "screening third-move predecessors: completed=" + completed.get() + "/"
                    + intermediateCount + " propagated=" + propagated.get() + " cached=" + cached.get());
            intermediates.forEachConcurrently(workers, intermediateLookup, state -> {
                cached.incrementAndGet();
                completed.incrementAndGet();
            }, state -> {
                requireState(state, 9, 18, 19);
                if (!intermediateClosure.test(state.pair())) {
                    propagated.incrementAndGet();
                    JointInverseReconstruction.middle(state.pair(), pair -> middles.add(pair, 10));
                }
                completed.incrementAndGet();
            });
            final long middleCount = middles.seal();
            final var checkpoint = new MiddleReconstructionCheckpoint(
                    endpointCount, intermediateCount, propagated.get(), middleCount);
            checkpoint.write(work);
            log.info("Middle-move reconstruction: propagatedIntermediates={}, middleClasses={}",
                    checkpoint.propagatedIntermediates(), middleCount);
            return searchMiddles(checkpoint, middles, work, middleClosure, rootClosure, lookup, workers, progress);
        }
    }

    private static Result searchMiddles(MiddleReconstructionCheckpoint middleCheckpoint,
                                        DiskStateSet middles, Path work,
                                        Predicate<CyclicTargetPair> middleClosure,
                                        Predicate<CyclicTargetPair> rootClosure,
                                        Function<List<DiskStateSet.State>, boolean[]> lookup,
                                        int workers, Progress progress) {
        try (var roots = new DiskStateSet(work.resolve("roots"))) {
            final long middleCount = middles.seal();
            final var completed = new AtomicLong();
            final var cached = new AtomicLong();
            final var propagated = new AtomicLong();
            final var middleLookup = checkedLookup(lookup, 10, 20);
            progress.phase(() -> "screening middle-move predecessors: completed=" + completed.get() + "/"
                    + middleCount + " propagated=" + propagated.get() + " cached=" + cached.get());
            middles.forEachConcurrently(workers, middleLookup, state -> {
                cached.incrementAndGet();
                completed.incrementAndGet();
            }, state -> {
                requireState(state, 10, 20);
                if (!middleClosure.test(state.pair())) {
                    propagated.incrementAndGet();
                    JointInverseReconstruction.roots(state.pair(), pair -> roots.add(pair, 11));
                }
                completed.incrementAndGet();
            });
            final long rootCount = roots.seal();
            final var checkpoint = new RootReconstructionCheckpoint(
                    middleCheckpoint.endpoints(), middleCheckpoint.intermediates(),
                    middleCheckpoint.propagatedIntermediates(), middleCount, propagated.get(), rootCount);
            checkpoint.write(work);
            log.info("First-move reconstruction: propagatedMiddles={}, rootClasses={}",
                    checkpoint.propagatedMiddles(), rootCount);
            return searchRoots(checkpoint, roots, rootClosure, lookup, workers, progress);
        }
    }

    private static Result searchRoots(RootReconstructionCheckpoint checkpoint, DiskStateSet roots,
                                      Predicate<CyclicTargetPair> rootClosure,
                                      Function<List<DiskStateSet.State>, boolean[]> lookup,
                                      int workers, Progress progress) {
        final var completed = new AtomicLong();
        final var cached = new AtomicLong();
        final var unresolved = new AtomicLong();
        final var rootLookup = checkedLookup(lookup, 11, 21);
        progress.phase(() -> "searching reconstructed roots: completed=" + completed.get() + "/"
                + checkpoint.roots() + " unresolved=" + unresolved.get() + " cached=" + cached.get());
        roots.forEachConcurrently(workers, rootLookup, state -> {
            cached.incrementAndGet();
            completed.incrementAndGet();
        }, state -> {
            requireState(state, 11, 21);
            if (!rootClosure.test(state.pair())) unresolved.incrementAndGet();
            completed.incrementAndGet();
        });
        return new Result(checkpoint.endpoints(), checkpoint.intermediates(),
                checkpoint.propagatedIntermediates(), checkpoint.middles(), checkpoint.propagatedMiddles(),
                checkpoint.roots(), unresolved.get());
    }

    private static Function<List<DiskStateSet.State>, boolean[]> checkedLookup(
            Function<List<DiskStateSet.State>, boolean[]> lookup, int budget, int... domains) {
        if (lookup == null) return null;
        return batch -> {
            for (var state : batch) requireState(state, budget, domains);
            return lookup.apply(batch);
        };
    }

    private static void requireState(DiskStateSet.State state, int budget, int... domains) {
        final int actualDomain = state.pair().getBeta().size();
        if (state.maximumMoves() != budget || Arrays.stream(domains).noneMatch(domain -> domain == actualDomain))
            throw new IllegalStateException("Reconstruction state must have domain "
                    + Arrays.toString(domains) + " and budget " + budget);
    }

    private static final class Progress implements AutoCloseable {
        private volatile Supplier<String> current = () -> "initializing";
        private final java.util.concurrent.ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("reconstruction-progress").factory());
        Progress() {
            reporter.scheduleWithFixedDelay(() -> log.info("Reconstruction progress: {}", current.get()),
                    10, 10, TimeUnit.SECONDS);
        }
        void phase(Supplier<String> phase) { current = phase; }
        @Override public void close() { reporter.shutdownNow(); }
    }
}
