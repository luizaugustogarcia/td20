package br.unb.cic.td20.emission;
import br.unb.cic.td20.joint.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.LongAdder;
import static org.junit.jupiter.api.Assertions.*;
class ActiveErikssonEmitterTest {
    @Test void publishedZeroDirectCountSkipsGenerationIncludingSupportClearedRows() throws Exception {
        // A deliberately mismatched positive small-domain count makes any
        // regeneration fail: emitter scheduling must use the published zero.
        for (long cleared : new long[]{0,2}) {
            var row=new ErikssonInventory.Root(1,new int[]{7},0,cleared,cleared);
            try (var progress=new ErikssonWitnessEmitter.Progress()) {
                var result=ErikssonWitnessEmitter.emitRoot(row,(pair,budget)->{
                    fail("A zero-direct partition must not dispatch sorting"); return false;
                },progress);
                assertEquals(0,result.unoriented());
                assertEquals(cleared,result.oriented());
                assertEquals(cleared,result.supportCleared());
                assertEquals(0,result.unresolved());
            }
        }
    }
    @Test void queuedRootFailuresAreObservedAfterTheQueueIsDrained() throws Exception {
        int[] partition={7};
        var counts=ErikssonRootGenerator.count(partition,2,new LongAdder()); assertTrue(counts.direct()>0);
        var row=new ErikssonInventory.Root(1,partition,counts.unoriented(),counts.oriented(),counts.supportCleared());
        LongAdder called=new LongAdder();
        try (var progress=new ErikssonWitnessEmitter.Progress()) {
            var result=ErikssonWitnessEmitter.emitRoot(row,(pair,budget)->{
                assertEquals(ErikssonRoute.ROOT_BUDGET,budget);
                try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                called.increment(); return false;
            },progress);
            assertEquals(counts.direct(),called.sum()); assertEquals(counts.direct(),result.unresolved());
            var pending=new ErikssonInventory.Root(1,partition,-1,-1,-1);
            assertThrows(IllegalStateException.class,()->ErikssonWitnessEmitter.emitRoot(pending,(p,b)->true,progress));
        }
    }
}
