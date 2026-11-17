package br.unb.cic.td20.fleet;

import br.unb.cic.td20.avoidance.*;
import br.unb.cic.td20.certificate.CertificateCodec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetBatchStreamingTest {
    private static final String RUN="0f97434b-941b-4424-990e-eb6cb073c9d8";
    @Test void dispatcherStartsComputationBeforeTheProducerFinishesAndReusesClosedResults(@TempDir Path directory) throws Exception {
        var tasks=ReconstructionBatches.describe(RUN,2,1);
        Path jobs=Files.createDirectory(directory.resolve("jobs"));
        for(var task:tasks) task.write(Files.createDirectory(jobs.resolve(task.id())));
        var started=new CountDownLatch(1); var calls=new AtomicInteger();
        var refreshed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var progress=new FleetProgress(directory.resolve("emission.log"))) {
            var emitter=mockEmitter(directory,progress,(server,command,input,output,log,observer,secret) -> {
                mockHardware(output);
                if(command.contains("fetch --no-tags") && command.contains("reset --mixed FETCH_HEAD")) refreshed.set(true);
                if(command.contains(" fleet-worker ")) { calls.incrementAndGet(); started.countDown(); }
            });
            emitter.dispatchPublished(tasks,jobs,directory.resolve("source.tar"),ready -> {
                ready.accept(tasks.getFirst());
                assertTrue(started.await(10,TimeUnit.SECONDS),"Worker must start while the producer has unpublished work");
                ready.accept(tasks.getLast());
            });
            assertEquals(2,calls.get()); assertTrue(refreshed.get());
            var reused=mockEmitter(directory,progress,(server,command,input,output,log,observer,secret) -> fail("Closed results must not rerun"));
            reused.dispatchPublished(tasks,jobs,directory.resolve("source.tar"),ready -> tasks.forEach(ready));
            assertThrows(IllegalStateException.class,() -> reused.dispatchPublished(tasks,jobs,directory.resolve("source.tar"),ready -> ready.accept(tasks.getFirst())));
        }
    }
    @Test void producerFailureInterruptsAnActiveRemoteWorker(@TempDir Path directory) throws Exception {
        var tasks=ReconstructionBatches.describe(RUN,2,1);
        Path jobs=Files.createDirectory(directory.resolve("jobs"));
        tasks.getFirst().write(Files.createDirectory(jobs.resolve(tasks.getFirst().id())));
        var started=new CountDownLatch(1); var interrupted=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var progress=new FleetProgress(directory.resolve("emission.log"));var executor=Executors.newSingleThreadExecutor()) {
            var emitter=mockEmitter(directory,progress,(server,command,input,output,log,observer,secret) -> {
                mockHardware(output);
                if(command.contains(" fleet-worker ")) {
                    started.countDown();
                    try { release.await(); } catch(InterruptedException error) { interrupted.countDown(); throw error; }
                }
            });
            var dispatch=executor.submit(() -> {
                try { emitter.dispatchPublished(tasks,jobs,directory.resolve("source.tar"),ready -> {
                    ready.accept(tasks.getFirst()); assertTrue(started.await(10,TimeUnit.SECONDS));
                    throw new IllegalStateException("Broken producer");
                }); } catch(Exception error) { throw new CompletionException(error); }
            });
            try { assertThrows(ExecutionException.class,() -> dispatch.get(10,TimeUnit.SECONDS)); assertEquals(0,interrupted.getCount()); }
            finally { release.countDown(); }
            assertFalse(Files.exists(directory.resolve("transfers/reconstruction/"+tasks.getFirst().id()+".txt")));
        }
    }
    @Test void producerFailureDuringServerPreparationDoesNotWaitForUnstartedCompletions(@TempDir Path directory) throws Exception {
        var tasks=ReconstructionBatches.describe(RUN,1,1);
        try(var progress=new FleetProgress(directory.resolve("emission.log"));var executor=Executors.newSingleThreadExecutor()) {
            var emitter=mockEmitter(directory,progress,(server,command,input,output,log,observer,secret) -> {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                mockHardware(output);
            });
            var dispatch=executor.submit(() -> {
                try { emitter.dispatchPublished(tasks,directory,directory.resolve("source.tar"),ready -> { throw new IllegalStateException("Immediate failure"); }); }
                catch(Exception error) { throw new CompletionException(error); }
            });
            assertThrows(ExecutionException.class,() -> dispatch.get(10,TimeUnit.SECONDS));
        }
    }
    private static FleetEmitter mockEmitter(Path directory,FleetProgress progress,FleetEmitter.Transport transport) {
        var server=new FleetServer("local",List.of("ssh","local"),"/workspace/mock","cpu","-R-Xmx128m -R-XX:ActiveProcessorCount=2 -R-Dtd20.twistedBraceletWorkers=2");
        return new FleetEmitter(directory,directory,List.of(server),RUN,1,progress,transport,"https://github.com/example/td20.git",null);
    }
    private static void mockHardware(Path output) throws java.io.IOException {
        if(output!=null) FleetFiles.write(output,new FleetHardware(2,1,1,1L<<30,List.of()).lines());
    }
    @Test void sealedBatchesPublishConcurrentlyBeforeAllPreparationFinishes(@TempDir Path directory) throws Exception {
        String previous=System.getProperty("td20.fleetBatchWorkers"); System.setProperty("td20.fleetBatchWorkers","2");
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        try(var source=source(directory.resolve("source"));var executor=Executors.newSingleThreadExecutor()) {
            Path jobs=Files.createDirectory(directory.resolve("jobs"));
            var future=executor.submit(() -> ReconstructionBatches.split(RUN,jobs,source,2,2,task -> {
                Path input=jobs.resolve(task.id()).resolve("input");
                assertEquals(task.inputCount(),ErikssonCheckpoint.read(input).afterTwo());
                try(var batch=DiskStateSet.openSealed(input.resolve("after-two"),task.inputCount())) { }
                entered.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new IllegalStateException(e); }
            }));
            try { assertTrue(entered.await(10,TimeUnit.SECONDS),"Two batches must publish independently"); assertFalse(future.isDone()); }
            finally { release.countDown(); }
            var tasks=future.get(10,TimeUnit.SECONDS);
            assertEquals(List.of(2L,2L,1L),tasks.stream().map(FleetTask::inputCount).toList());
            assertEquals(tasks,ReconstructionBatches.split(RUN,jobs,source,2,2),"Resume revalidates and retains the same sorted slices");
            Files.delete(jobs.resolve(tasks.getLast().id()).resolve("task.txt"));
            assertEquals(tasks,ReconstructionBatches.split(RUN,jobs,source,2,2),"An unpublished legacy batch is regenerated");
            var wrong=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,3,2,-1,-1);
            wrong.write(jobs.resolve(tasks.getFirst().id()).resolve("input"));
            assertThrows(Exception.class,() -> ReconstructionBatches.split(RUN,jobs,source,2,2));
        } finally { if(previous==null)System.clearProperty("td20.fleetBatchWorkers");else System.setProperty("td20.fleetBatchWorkers",previous); }
    }
    @Test void workerComputesFirstPublicationWhileLaterBatchesAreStillUnprepared() throws Exception {
        var a=new FleetTask(RUN,"root-a","roots",1,1,0); var b=new FleetTask(RUN,"root-b","roots",2,2,0);
        var queue=new FleetTaskQueue(List.of(a,b),Set.of(),1); var remaining=new AtomicInteger(2);
        var firstDone=new CountDownLatch(1); var processed=ConcurrentHashMap.<String>newKeySet();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var worker=executor.submit(() -> { try { FleetEmitter.drainPublishedTasks(queue,remaining,task -> {
                assertTrue(processed.add(task.id())); firstDone.countDown();
            },error -> fail(error)); } catch(Exception e) { throw new CompletionException(e); } });
            queue.publish(a); assertTrue(firstDone.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,queue::finish,"Missing future batches cannot close production");
            queue.publish(b); queue.finish(); worker.get(5,TimeUnit.SECONDS);
            assertEquals(0,remaining.get()); assertEquals(Set.of(a.id(),b.id()),processed);
            assertThrows(IllegalArgumentException.class,() -> queue.publish(a));
        }
    }
    @Test void failureUnblocksBackpressuredPublishersAndReusedResultsStillRequireEveryBatch() throws Exception {
        var a=new FleetTask(RUN,"root-a","roots",1,1,0); var b=new FleetTask(RUN,"root-b","roots",2,2,0);
        var queue=new FleetTaskQueue(List.of(a,b),Set.of(),1); queue.publish(a);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var blocked=executor.submit(() -> queue.publish(b));
            queue.abort(new IllegalStateException("Preparation failed"));
            assertThrows(ExecutionException.class,() -> blocked.get(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,queue::finish);
        }
        var reused=new FleetTaskQueue(List.of(a,b),Set.of(a.id(),b.id()),1);
        reused.publish(a); assertThrows(IllegalStateException.class,reused::finish); reused.publish(b); reused.finish();
    }
    @Test void fastPersistenceUsesTheExistingCheckedStateFormatAndRejectsDuplicateKeys(@TempDir Path directory) {
        try(var source=source(directory.resolve("source"))) {
            var states=new ArrayList<DiskStateSet.State>(); source.forEach(states::add);
            DiskStateSet.writeSealedBatch(directory.resolve("batch"),states);
            var actual=new ArrayList<String>();
            try(var batch=DiskStateSet.openSealed(directory.resolve("batch"),5)) { batch.forEach(state -> actual.add(key(state))); }
            assertEquals(states.stream().map(FleetBatchStreamingTest::key).toList(),actual);
            assertThrows(IllegalArgumentException.class,() -> DiskStateSet.writeSealedBatch(directory.resolve("duplicate"),List.of(states.getFirst(),states.getFirst())));
        }
    }
    private static String key(DiskStateSet.State state) { return HexFormat.of().formatHex(CertificateCodec.keyFor(state.pair())); }
    private static DiskStateSet source(Path path) {
        var result=new DiskStateSet(path); var random=new Random(17);var keys=new HashSet<String>();
        while(keys.size()<5) {
            var pair=FleetTest.randomPair(18,random).canonicalRepresentative();
            if(pair.getOmega().stream().anyMatch(cycle -> cycle.size()==1)) continue;
            if(keys.add(HexFormat.of().formatHex(CertificateCodec.keyFor(pair))))result.add(pair,9);
        }
        assertEquals(5,result.seal());return result;
    }
}
