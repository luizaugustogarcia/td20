package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class ActiveErikssonAvoidanceTest {
    @Test void everyResumedWaveChecksItsCountDuringProcessing(@TempDir Path directory) throws Exception {
        for (var stage:ErikssonCheckpoint.Stage.values()) {
            Path source=Files.createDirectory(directory.resolve(stage.name()));
            String name=switch (stage) {
                case AFTER_TWO -> "after-two";
                case AFTER_ONE -> "after-one";
                case ROOTS -> "roots";
            };
            int symbols=stage==ErikssonCheckpoint.Stage.AFTER_TWO ? ErikssonRoute.ROOT_SYMBOLS-3:ErikssonRoute.ROOT_SYMBOLS;
            int budget=switch (stage) {
                case AFTER_TWO -> ErikssonRoute.ROOT_BUDGET-2;
                case AFTER_ONE -> ErikssonRoute.ROOT_BUDGET-1;
                case ROOTS -> ErikssonRoute.ROOT_BUDGET;
            };
            try (var set=new DiskStateSet(source.resolve(name))) {
                set.add(pair(symbols),budget); assertEquals(1,set.seal());
            }
            var checkpoint=switch (stage) {
                case AFTER_TWO -> new ErikssonCheckpoint(stage,1,2,-1,-1);
                case AFTER_ONE -> new ErikssonCheckpoint(stage,1,1,2,-1);
                case ROOTS -> new ErikssonCheckpoint(stage,1,1,1,2);
            };
            checkpoint.write(source);
            var processed=new java.util.concurrent.atomic.AtomicInteger();
            Path work=Files.createDirectory(directory.resolve(stage.name()+"-work"));
            assertThrows(IllegalStateException.class,()->ErikssonAvoidance.resume(source,work,(p,b)->{
                assertEquals(budget,b); processed.incrementAndGet(); return true;
            },null));
            assertEquals(1,processed.get(),"The mandatory scan must process records before rejecting the count mismatch: "+stage);
            assertEquals(checkpoint,ErikssonCheckpoint.read(source));
            assertThrows(IllegalStateException.class,()->ErikssonCheckpoint.read(work));
        }
    }

    @Test void splitStagesPreserveEveryRootInBothInverseBranches(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));
        try (var set=new DiskStateSet(source.resolve("after-one"))) {
            set.add(pair(ErikssonRoute.ROOT_SYMBOLS-1),ErikssonRoute.ROOT_BUDGET-1);
            set.add(pair(ErikssonRoute.ROOT_SYMBOLS),ErikssonRoute.ROOT_BUDGET-1);
            assertEquals(2,set.seal());
        }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,1,2,-1).write(source);
        Set<ExtendedToricClassKey> expected=ConcurrentHashMap.newKeySet(),actual=ConcurrentHashMap.newKeySet();
        var baseline=ErikssonAvoidance.resume(source,Files.createDirectory(directory.resolve("baseline")),(p,b)->{
            if (b<ErikssonRoute.ROOT_BUDGET) return false;
            // CPU settlement may see a class in advisory prefetch and again in the mandatory scan.
            expected.add(p.extendedToricClassKey()); return true;
        },null);
        var split=ErikssonAvoidance.resume(source,Files.createDirectory(directory.resolve("split")),
                (p,b)->false,(p,b)->{
                    if (b<ErikssonRoute.ROOT_BUDGET) return false;
                    // Advisory prefetch may check a class before the mandatory final scan.
                    actual.add(p.extendedToricClassKey()); return true;
                },null);
        assertFalse(expected.isEmpty()); assertEquals(expected,actual); assertEquals(baseline,split);
    }
    @Test void resumedAfterTwoWaveProducesBothAfterOneDomains(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source")),work=Files.createDirectory(directory.resolve("next"));
        try (var set=new DiskStateSet(source.resolve("after-two"))) {
            set.add(pair(ErikssonRoute.ROOT_SYMBOLS-3),ErikssonRoute.ROOT_BUDGET-2); assertEquals(1,set.seal());
        }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,1,1,-1,-1).write(source);
        Set<Integer> domains=ConcurrentHashMap.newKeySet();
        var result=ErikssonAvoidance.resume(source,work,(p,b)->{
            if (b==ErikssonRoute.ROOT_BUDGET-2) return false;
            assertEquals(ErikssonRoute.ROOT_BUDGET-1,b); domains.add(p.getBeta().size()); return true;
        },null);
        assertEquals(Set.of(ErikssonRoute.ROOT_SYMBOLS-1,ErikssonRoute.ROOT_SYMBOLS),domains);
        assertTrue(result.afterOne()>1); assertEquals(0,result.roots()); assertEquals(0,result.unresolved());
        var resumed=ErikssonAvoidance.resume(work,Files.createDirectory(directory.resolve("again")),(p,b)->{fail("Empty sealed root wave must not generate work");return false;},null);
        assertEquals(result,resumed);
    }
    @Test void resumedMixedAfterOneWaveReversesGainOneAndGainZero(@TempDir Path directory) throws Exception {
        Path source=Files.createDirectory(directory.resolve("source")),work=Files.createDirectory(directory.resolve("next"));
        try (var set=new DiskStateSet(source.resolve("after-one"))) {
            set.add(pair(ErikssonRoute.ROOT_SYMBOLS-1),ErikssonRoute.ROOT_BUDGET-1);
            set.add(pair(ErikssonRoute.ROOT_SYMBOLS),ErikssonRoute.ROOT_BUDGET-1); assertEquals(2,set.seal());
        }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,1,2,-1).write(source);
        Set<Integer> domains=ConcurrentHashMap.newKeySet();
        var result=ErikssonAvoidance.resume(source,work,(p,b)->{
            if (b==ErikssonRoute.ROOT_BUDGET-1) { domains.add(p.getBeta().size()); return false; }
            assertEquals(ErikssonRoute.ROOT_BUDGET,b); assertEquals(ErikssonRoute.ROOT_SYMBOLS,p.getBeta().size()); return true;
        },null);
        assertEquals(Set.of(ErikssonRoute.ROOT_SYMBOLS-1,ErikssonRoute.ROOT_SYMBOLS),domains);
        assertTrue(result.roots()>0); assertEquals(0,result.unresolved());
    }
    @Test void oldCheckpointsMissingSetsAndWrongBudgetsCannotResume(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("reconstruction-checkpoint.txt"),"incompatible checkpoint");
        assertThrows(IllegalStateException.class,()->ErikssonCheckpoint.read(directory));
        var checkpoint=new ErikssonCheckpoint(ErikssonCheckpoint.Stage.ROOTS,1,1,1,1); checkpoint.write(directory);
        assertEquals(checkpoint,ErikssonCheckpoint.read(directory));
        assertThrows(IllegalStateException.class,()->ErikssonAvoidance.resume(directory,directory.resolve("work"),(p,b)->true,null));
        assertThrows(IllegalArgumentException.class,()->new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_TWO,1,1,0,-1));
        Path source=Files.createDirectory(directory.resolve("wrong")),work=Files.createDirectory(directory.resolve("wrong-next"));
        try (var set=new DiskStateSet(source.resolve("after-one"))) { set.add(pair(ErikssonRoute.ROOT_SYMBOLS),ErikssonRoute.ROOT_BUDGET); set.seal(); }
        new ErikssonCheckpoint(ErikssonCheckpoint.Stage.AFTER_ONE,1,1,1,-1).write(source);
        assertThrows(IllegalStateException.class,()->ErikssonAvoidance.resume(source,work,(p,b)->true,null));
    }
    static CyclicTargetPair pair(int n) {
        int shift=1; while (gcd(shift+1,n)!=1) shift++;
        int[] image=new int[n],beta=new int[n]; for (int i=0;i<n;i++) { image[i]=(i+shift)%n; beta[i]=i; }
        var omega=new MulticyclePermutation(); boolean[] seen=new boolean[n];
        for (int x=0;x<n;x++) if (!seen[x]) {
            List<Integer> symbols=new ArrayList<>(); int y=x;
            do { symbols.add(y); seen[y]=true; y=image[y]; } while (y!=x);
            omega.add(Cycle.of(symbols.stream().mapToInt(Integer::intValue).toArray()));
        }
        return CyclicTargetPair.ofOwned(omega,Cycle.of(beta)).canonicalRepresentative();
    }
    static int gcd(int a,int b) { while (b!=0) { int t=a%b;a=b;b=t; } return a; }
}
