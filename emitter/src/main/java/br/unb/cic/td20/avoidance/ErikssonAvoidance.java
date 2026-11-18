package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.compat.LegacyPairs;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Consumer;

/** Three inverse waves, both gain branches, both endpoints, and disk-backed deduplication. */
public final class ErikssonAvoidance {
    private ErikssonAvoidance() { }
    public record Result(long endpoints,long afterTwo,long afterOne,long roots,long unresolved) { }
    public static Result resolve(DiskStateSet endpoints,Path work,BiPredicate<CyclicTargetPair,Integer> discharge,
                                 Function<List<DiskStateSet.State>,boolean[]> lookup) {
        return resolve(endpoints,work,discharge,(pair,budget)->false,lookup);
    }
    public static Result resolve(DiskStateSet endpoints,Path work,BiPredicate<CyclicTargetPair,Integer> cpu,
                                 BiPredicate<CyclicTargetPair,Integer> gpu,
                                 Function<List<DiskStateSet.State>,boolean[]> lookup) {
        return resolve(endpoints,work,cpu,gpu,lookup,gpu);
    }
    public static Result resolve(DiskStateSet endpoints,Path work,BiPredicate<CyclicTargetPair,Integer> cpu,
                                 BiPredicate<CyclicTargetPair,Integer> gpu,
                                 Function<List<DiskStateSet.State>,boolean[]> lookup,
                                 BiPredicate<CyclicTargetPair,Integer> prefetchGpu) {
        return resolvePrimitive(endpoints,work,adapt(cpu),adapt(gpu),lookup,adapt(prefetchGpu));
    }
    public static Result resolvePrimitive(DiskStateSet endpoints,Path work,BiPredicate<PrimitivePair,Integer> cpu,
                                 BiPredicate<PrimitivePair,Integer> gpu,
                                 Function<List<DiskStateSet.State>,boolean[]> lookup,
                                 BiPredicate<PrimitivePair,Integer> prefetchGpu) {
        long count=endpoints.seal();
        var completed = new LongAdder();
        try (var afterTwo=new DiskStateSet(work.resolve("after-two"))) {
            try (var progress=new ReconstructionProgress("Inverse reconstruction after-two generation (endpoint classes)", count, completed::sum)) {
            endpoints.forEachConcurrently(workers(),state -> {
                require(state,0);
                afterTwo.addInverseGenerated(state.primitivePair(),ErikssonRoute.ROOT_BUDGET-2,2);
                completed.increment();
            });
            }
            var checkpoint=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,count,afterTwo.seal(),-1,-1);
            checkpoint.write(work);
            return afterTwo(checkpoint,afterTwo,work,cpu,gpu,lookup,prefetchGpu);
        }
    }
    public static Result resume(Path source,Path work,BiPredicate<CyclicTargetPair,Integer> discharge,
                                Function<List<DiskStateSet.State>,boolean[]> lookup) {
        return resume(source,work,discharge,(pair,budget)->false,lookup);
    }
    public static Result resume(Path source,Path work,BiPredicate<CyclicTargetPair,Integer> cpu,
                                BiPredicate<CyclicTargetPair,Integer> gpu,
                                Function<List<DiskStateSet.State>,boolean[]> lookup) {
        return resume(source,work,cpu,gpu,lookup,gpu);
    }
    public static Result resume(Path source,Path work,BiPredicate<CyclicTargetPair,Integer> cpu,
                                BiPredicate<CyclicTargetPair,Integer> gpu,
                                Function<List<DiskStateSet.State>,boolean[]> lookup,
                                BiPredicate<CyclicTargetPair,Integer> prefetchGpu) {
        return resumePrimitive(source,work,adapt(cpu),adapt(gpu),lookup,adapt(prefetchGpu));
    }
    public static Result resumePrimitive(Path source,Path work,BiPredicate<PrimitivePair,Integer> cpu,
                                BiPredicate<PrimitivePair,Integer> gpu,
                                Function<List<DiskStateSet.State>,boolean[]> lookup,
                                BiPredicate<PrimitivePair,Integer> prefetchGpu) {
        var checkpoint=ErikssonCheckpoint.read(source);
        String name=checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_TWO ? "after-two":checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_ONE ? "after-one":"roots";
        long count=checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_TWO ? checkpoint.afterTwo():checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_ONE ? checkpoint.afterOne():checkpoint.roots();
        // Every resumed wave validates its inventory during mandatory processing.
        try (var states=checkpoint.afterOneCountPending()?DiskStateSet.openCompletedForScan(source.resolve(name))
                :DiskStateSet.openSealedForScan(source.resolve(name),count)) {
            return switch (checkpoint.stage()) {
                case AFTER_TWO -> afterTwo(checkpoint,states,work,cpu,gpu,lookup,prefetchGpu);
                case AFTER_ONE -> afterOne(checkpoint,states,source,work,cpu,gpu,lookup,prefetchGpu);
                case ROOTS -> roots(checkpoint,states,work,cpu,gpu,lookup);
            };
        }
    }
    private static Result afterTwo(ErikssonCheckpoint checkpoint,DiskStateSet states,Path work,
                                    BiPredicate<PrimitivePair,Integer> cpu,BiPredicate<PrimitivePair,Integer> gpu,
                                    Function<List<DiskStateSet.State>,boolean[]> lookup,
                                    BiPredicate<PrimitivePair,Integer> prefetchGpu) {
        try (var afterOne=new DiskStateSet(work.resolve("after-one"))) {
            Consumer<DiskStateSet.State> expand=state -> {
                require(state,1);
                afterOne.addInverseGenerated(state.primitivePair(),ErikssonRoute.ROOT_BUDGET-1,2,3);
            };
            if(checkpoint.mode()==ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION) expandOnly(states,"after-two residuals",checkpoint.afterTwo(),expand);
            else ReconstructionPipeline.processPrimitive(states,work.resolve("after-two-search.queue"),workers(),
                    ReconstructionPipeline.configuredGpuWorkers(),checked(lookup,1),cpu,gpu,expand);
            // Residual generation already enforces state invariants. Drain writers now;
            // derive the exact logical-key count during the required final inverse expansion.
            long afterOneCount=checkpoint.mode()==ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION?afterOne.prepareScan():afterOne.seal();
            var next=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,checkpoint.endpoints(),checkpoint.afterTwo(),afterOneCount,-1,checkpoint.mode());
            next.write(work); return afterOne(next,afterOne,work,work,cpu,gpu,lookup,prefetchGpu);
        }
    }
    private static Result afterOne(ErikssonCheckpoint checkpoint,DiskStateSet states,Path checkpointSource,Path work,
                                    BiPredicate<PrimitivePair,Integer> cpu,BiPredicate<PrimitivePair,Integer> gpu,
                                    Function<List<DiskStateSet.State>,boolean[]> lookup,
                                    BiPredicate<PrimitivePair,Integer> prefetchGpu) {
        var layout=new GpuDeviceLayout(
                GpuDeviceLayout.parseDevices(System.getProperty("td20.gpuDevices","0")),
                Integer.getInteger("td20.gpuSlots",8));
        try (var prefetch=RootPrefetch.primitive(layout.totalSlots(),cpu,prefetchGpu,checked(lookup,3));
             var roots=new DiskStateSet(work.resolve("roots"),prefetch::publish)) {
            prefetch.activate();
            Consumer<DiskStateSet.State> expand=state -> {
                require(state,2);
                int gain=state.primitivePair().size()==ErikssonRoute.ROOT_SYMBOLS-1 ? 1:0;
                roots.addInverseGenerated(state.primitivePair(),ErikssonRoute.ROOT_BUDGET,gain);
            };
            if(checkpoint.mode()==ErikssonCheckpoint.Mode.RESIDUAL_EXPANSION) expandOnly(states,"after-one inverse states",checkpoint.afterOne(),expand);
            else ReconstructionPipeline.processPrimitive(states,work.resolve("after-one-search.queue"),workers(),
                    ReconstructionPipeline.configuredGpuWorkers(),checked(lookup,2),cpu,gpu,expand);
            long afterOneCount=states.knownSize();
            if(afterOneCount<0) throw new IllegalStateException("Completed intermediate expansion did not establish its exact count");
            var counted=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,checkpoint.endpoints(),
                    checkpoint.afterTwo(),afterOneCount,-1,checkpoint.mode());
            if(checkpoint.afterOneCountPending()) counted.write(checkpointSource);
            roots.prepareScan();
            prefetch.finish();
            // Count while processing roots; publish a counted checkpoint only
            // after the exhaustive scan and all search workers have joined.
            var result = roots(counted,roots,work,cpu,gpu,lookup);
            new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS,checkpoint.endpoints(),
                    checkpoint.afterTwo(),afterOneCount,result.roots(),checkpoint.mode()).write(work);
            return result;
        }
    }
    private static Result roots(ErikssonCheckpoint checkpoint,DiskStateSet states,Path work,
                                 BiPredicate<PrimitivePair,Integer> cpu,BiPredicate<PrimitivePair,Integer> gpu,
                                 Function<List<DiskStateSet.State>,boolean[]> lookup) {
        AtomicLong unresolved=new AtomicLong();
        ReconstructionPipeline.processPrimitive(states,work.resolve("roots-search.queue"),workers(),
                ReconstructionPipeline.configuredGpuWorkers(),checked(lookup,3),cpu,gpu,state -> {
            require(state,3); unresolved.incrementAndGet();
        });
        return new Result(checkpoint.endpoints(),checkpoint.afterTwo(),checkpoint.afterOne(),states.knownSize(),unresolved.get());
    }
    private static int workers() { return DirectTwistedBraceletGenerator.configuredWorkerCount(); }
    private static void expandOnly(DiskStateSet states,String name,long expected,Consumer<DiskStateSet.State> output) {
        var completed=new LongAdder();
        try(var progress=new ReconstructionProgress("Inverse expansion "+name,expected,completed::sum)) {
            states.forEachConcurrently(workers(),state -> { output.accept(state);completed.increment(); });
        }
    }
    private static Function<List<DiskStateSet.State>,boolean[]> checked(Function<List<DiskStateSet.State>,boolean[]> lookup,int stage) {
        return batch -> {
            for (var state:batch) require(state,stage);
            return lookup==null ? new boolean[batch.size()]:lookup.apply(batch);
        };
    }
    private static void require(DiskStateSet.State state,int stage) {
        int n=state.primitivePair().size(),budget=ErikssonRoute.ROOT_BUDGET;
        boolean valid=switch(stage) {
            case 0 -> n==ErikssonRoute.ENDPOINT_SYMBOLS && state.maximumMoves()==budget-3;
            case 1 -> n==ErikssonRoute.ROOT_SYMBOLS-3 && state.maximumMoves()==budget-2;
            case 2 -> (n==ErikssonRoute.ROOT_SYMBOLS-1 || n==ErikssonRoute.ROOT_SYMBOLS) && state.maximumMoves()==budget-1;
            case 3 -> n==ErikssonRoute.ROOT_SYMBOLS && state.maximumMoves()==budget;
            default -> false;
        };
        if (!valid) throw new IllegalStateException("Wrong domain or budget in Eriksson inverse stage "+stage);
    }
    private static BiPredicate<PrimitivePair,Integer> adapt(BiPredicate<CyclicTargetPair,Integer> legacy) {
        return (pair,budget) -> legacy.test(LegacyPairs.toLegacy(pair),budget);
    }
}
