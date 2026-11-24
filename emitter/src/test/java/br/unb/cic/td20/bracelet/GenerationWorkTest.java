package br.unb.cic.td20.bracelet;

import br.unb.cic.td20.model.ExtendedToricClassKey;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GenerationWorkTest {

    @Test
    void everyPrefixPartitionHasExactlyTheFullSmallDomainClassSet() {
        boolean foundLeaves = false, foundPrefixes = false;
        for (final int[] partition : List.of(new int[] { 3, 3 }, new int[] { 3, 2, 2 }, new int[] { 5, 3 }, new int[] { 4, 2, 2 })) {
            for (int annotation = 0; annotation < 2; annotation++) {
                val oriented = new boolean[partition.length];
                oriented[0] = annotation == 1;

                final Set<ExtendedToricClassKey> expected = new HashSet<>();
                DirectTwistedBraceletGenerator.generateRepresentatives(partition, oriented, pair -> assertTrue(expected.add(pair.extendedToricClassKey())));
                for (final int target : new int[] { 1, 3, 32, 256 }) {
                    val work = DirectTwistedBraceletGenerator.planWork(partition, oriented, target);
                    assertEquals(work, DirectTwistedBraceletGenerator.planWork(partition, oriented, target));

                    final Set<ExtendedToricClassKey> actual = ConcurrentHashMap.newKeySet();
                    for (val item : work) {
                        foundLeaves |= item.leavesOnly();
                        foundPrefixes |= !item.leavesOnly();
                        DirectTwistedBraceletGenerator.generateWork(partition,
                                oriented,
                                item,
                                () -> {
                                },
                                pair -> assertTrue(actual.add(pair.extendedToricClassKey()), "Overlapping generation tasks"));
                    }

                    assertEquals(expected, actual, "Missing classes for frontier target " + target);
                }
            }
        }

        assertTrue(foundLeaves);
        assertTrue(foundPrefixes);
    }

    @Test
    void malformedPathsWrongLeafCountsAndConsumerFailuresAreRejected() {
        final int[] partition = { 3, 3 };
        final boolean[] oriented = { true, false };
        val invalid = new DirectTwistedBraceletGenerator.GenerationWork(List.of(Integer.MAX_VALUE), false, -1);
        assertThrows(IllegalArgumentException.class,
                () -> DirectTwistedBraceletGenerator.generateWork(partition, oriented, invalid, () -> {
                }, pair -> fail()));

        val called = new AtomicBoolean();
        assertThrows(IllegalStateException.class,
                () -> DirectTwistedBraceletGenerator.generateWork(partition,
                        oriented,
                        new DirectTwistedBraceletGenerator.GenerationWork(List.of(), false, -1),
                        () -> {
                        },
                        pair -> {
                            called.set(true);
                            throw new IllegalArgumentException("failed");
                        }));
        assertTrue(called.get());

        val leaf = DirectTwistedBraceletGenerator.planWork(partition, oriented, 256).stream().filter(DirectTwistedBraceletGenerator.GenerationWork::leavesOnly).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> DirectTwistedBraceletGenerator.generateWork(partition,
                        oriented,
                        new DirectTwistedBraceletGenerator.GenerationWork(leaf.path(), true, leaf.expectedLeaves() + 1),
                        () -> {
                        },
                        pair -> {
                        }));
    }
}
