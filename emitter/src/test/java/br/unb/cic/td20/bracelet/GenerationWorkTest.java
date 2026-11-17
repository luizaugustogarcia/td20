package br.unb.cic.td20.bracelet;

import br.unb.cic.td20.model.ExtendedToricClassKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationWorkTest {
    @Test void everyPrefixPartitionHasExactlyTheFullSmallDomainClassSet() {
        boolean foundLeaves = false, foundPrefixes = false;
        for (int[] partition : List.of(new int[]{3,3},new int[]{3,2,2},new int[]{5,3},new int[]{4,2,2})) {
            for (int annotation = 0; annotation < 2; annotation++) {
                boolean[] oriented = new boolean[partition.length]; oriented[0] = annotation == 1;
                Set<ExtendedToricClassKey> expected = new HashSet<>();
                DirectTwistedBraceletGenerator.generateRepresentatives(partition,oriented,pair -> assertTrue(expected.add(pair.extendedToricClassKey())));
                for (int target : new int[]{1,3,32,256}) {
                    var work = DirectTwistedBraceletGenerator.planWork(partition,oriented,target);
                    assertEquals(work,DirectTwistedBraceletGenerator.planWork(partition,oriented,target));
                    Set<ExtendedToricClassKey> actual = ConcurrentHashMap.newKeySet();
                    for (var item : work) {
                        foundLeaves |= item.leavesOnly(); foundPrefixes |= !item.leavesOnly();
                        DirectTwistedBraceletGenerator.generateWork(partition,oriented,item,() -> { },pair ->
                                assertTrue(actual.add(pair.extendedToricClassKey()),"Overlapping generation tasks"));
                    }
                    assertEquals(expected,actual,"Missing classes for frontier target " + target);
                }
            }
        }
        assertTrue(foundLeaves); assertTrue(foundPrefixes);
    }
    @Test void malformedPathsWrongLeafCountsAndConsumerFailuresAreRejected() {
        int[] partition = {3,3}; boolean[] oriented = {true,false};
        var invalid = new DirectTwistedBraceletGenerator.GenerationWork(List.of(Integer.MAX_VALUE),false,-1);
        assertThrows(IllegalArgumentException.class,() -> DirectTwistedBraceletGenerator.generateWork(partition,oriented,invalid,() -> { },pair -> fail()));
        var called = new AtomicBoolean();
        assertThrows(IllegalStateException.class,() -> DirectTwistedBraceletGenerator.generateWork(partition,oriented,
                new DirectTwistedBraceletGenerator.GenerationWork(List.of(),false,-1),() -> { },pair -> { called.set(true); throw new IllegalArgumentException("failed"); }));
        assertTrue(called.get());
        var leaf = DirectTwistedBraceletGenerator.planWork(partition,oriented,256).stream().filter(DirectTwistedBraceletGenerator.GenerationWork::leavesOnly).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,() -> DirectTwistedBraceletGenerator.generateWork(partition,oriented,
                new DirectTwistedBraceletGenerator.GenerationWork(leaf.path(),true,leaf.expectedLeaves() + 1),() -> { },pair -> { }));
    }
}
