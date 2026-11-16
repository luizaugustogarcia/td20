package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.base.GpuDeviceLayout;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.Function;

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
        long count=endpoints.seal();
        var completed = new LongAdder();
        try (var afterTwo=new DiskStateSet(work.resolve("after-two"))) {
            try (var progress=new ReconstructionProgress("Inverse reconstruction after-two generation (endpoint classes)", count, completed::sum)) {
            endpoints.forEachConcurrently(workers(),state -> {
                require(state,0);
                afterTwo.addGenerated(ErikssonRoute.ROOT_BUDGET-2,
                        output -> ErikssonInverseReconstruction.preimages(state.pair(),2,output));
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
        var checkpoint=ErikssonCheckpoint.read(source);
        String name=checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_TWO ? "after-two":checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_ONE ? "after-one":"roots";
        long count=checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_TWO ? checkpoint.afterTwo():checkpoint.stage()==ErikssonCheckpoint.Stage.AFTER_ONE ? checkpoint.afterOne():checkpoint.roots();
        // Every resumed wave validates its inventory during mandatory processing.
        try (var states=DiskStateSet.openSealedForScan(source.resolve(name),count)) {
            return switch (checkpoint.stage()) {
                case AFTER_TWO -> afterTwo(checkpoint,states,work,cpu,gpu,lookup,prefetchGpu);
                case AFTER_ONE -> afterOne(checkpoint,states,work,cpu,gpu,lookup,prefetchGpu);
                case ROOTS -> roots(checkpoint,states,work,cpu,gpu,lookup);
            };
        }
    }
    private static Result afterTwo(ErikssonCheckpoint checkpoint,DiskStateSet states,Path work,
                                    BiPredicate<CyclicTargetPair,Integer> cpu,BiPredicate<CyclicTargetPair,Integer> gpu,
                                    Function<List<DiskStateSet.State>,boolean[]> lookup,
                                    BiPredicate<CyclicTargetPair,Integer> prefetchGpu) {
        try (var afterOne=new DiskStateSet(work.resolve("after-one"))) {
            ReconstructionPipeline.process(states,work.resolve("after-two-search.queue"),workers(),
                    ReconstructionPipeline.configuredGpuWorkers(),checked(lookup,1),cpu,gpu,state -> {
                require(state,1);
                afterOne.addGenerated(ErikssonRoute.ROOT_BUDGET-1,output -> {
                    for (int gain:new int[]{2,3}) ErikssonInverseReconstruction.preimages(state.pair(),gain,output);
                });
            });
            var next=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,checkpoint.endpoints(),checkpoint.afterTwo(),afterOne.seal(),-1);
            next.write(work); return afterOne(next,afterOne,work,cpu,gpu,lookup,prefetchGpu);
        }
    }
    private static Result afterOne(ErikssonCheckpoint checkpoint,DiskStateSet states,Path work,
                                    BiPredicate<CyclicTargetPair,Integer> cpu,BiPredicate<CyclicTargetPair,Integer> gpu,
                                    Function<List<DiskStateSet.State>,boolean[]> lookup,
                                    BiPredicate<CyclicTargetPair,Integer> prefetchGpu) {
        var layout=new GpuDeviceLayout(
                GpuDeviceLayout.parseDevices(System.getProperty("td20.gpuDevices","0")),
                Integer.getInteger("td20.gpuSlots",8));
        try (var prefetch=new RootPrefetch(layout.totalSlots(),prefetchGpu,checked(lookup,3));
             var roots=new DiskStateSet(work.resolve("roots"),prefetch::publish)) {
            prefetch.activate();
            ReconstructionPipeline.process(states,work.resolve("after-one-search.queue"),workers(),
                    ReconstructionPipeline.configuredGpuWorkers(),checked(lookup,2),cpu,gpu,state -> {
                require(state,2);
                int gain=state.pair().getBeta().size()==ErikssonRoute.ROOT_SYMBOLS-1 ? 1:0;
                roots.addGenerated(ErikssonRoute.ROOT_BUDGET,
                        output -> ErikssonInverseReconstruction.preimages(state.pair(),gain,output));
            });
            roots.prepareScan();
            prefetch.finish();
            // Count while processing roots; publish a counted checkpoint only
            // after the exhaustive scan and all search workers have joined.
            var result = roots(checkpoint,roots,work,cpu,gpu,lookup);
            new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS,checkpoint.endpoints(),
                    checkpoint.afterTwo(),checkpoint.afterOne(),result.roots()).write(work);
            return result;
        }
    }
    private static Result roots(ErikssonCheckpoint checkpoint,DiskStateSet states,Path work,
                                 BiPredicate<CyclicTargetPair,Integer> cpu,BiPredicate<CyclicTargetPair,Integer> gpu,
                                 Function<List<DiskStateSet.State>,boolean[]> lookup) {
        AtomicLong unresolved=new AtomicLong();
        ReconstructionPipeline.process(states,work.resolve("roots-search.queue"),workers(),
                ReconstructionPipeline.configuredGpuWorkers(),checked(lookup,3),cpu,gpu,state -> {
            require(state,3); unresolved.incrementAndGet();
        });
        return new Result(checkpoint.endpoints(),checkpoint.afterTwo(),checkpoint.afterOne(),states.knownSize(),unresolved.get());
    }
    private static int workers() { return DirectTwistedBraceletGenerator.configuredWorkerCount(); }
    private static Function<List<DiskStateSet.State>,boolean[]> checked(Function<List<DiskStateSet.State>,boolean[]> lookup,int stage) {
        return batch -> {
            for (var state:batch) require(state,stage);
            return lookup==null ? new boolean[batch.size()]:lookup.apply(batch);
        };
    }
    private static void require(DiskStateSet.State state,int stage) {
        int n=state.pair().getBeta().size(),budget=ErikssonRoute.ROOT_BUDGET;
        boolean valid=switch(stage) {
            case 0 -> n==ErikssonRoute.ENDPOINT_SYMBOLS && state.maximumMoves()==budget-3;
            case 1 -> n==ErikssonRoute.ROOT_SYMBOLS-3 && state.maximumMoves()==budget-2;
            case 2 -> (n==ErikssonRoute.ROOT_SYMBOLS-1 || n==ErikssonRoute.ROOT_SYMBOLS) && state.maximumMoves()==budget-1;
            case 3 -> n==ErikssonRoute.ROOT_SYMBOLS && state.maximumMoves()==budget;
            default -> false;
        };
        if (!valid) throw new IllegalStateException("Wrong domain or budget in Eriksson inverse stage "+stage);
    }
}
