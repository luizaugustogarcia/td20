package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.reduction.Partitions;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JointParallelGenerationTest {
    @Test void parallelFrontierPreservesEveryClassAndEveryCountThroughNineSymbols() {
        for (int n = 3; n <= 9; n++) for (int[] partition : Partitions.generateValidPartitions(n)) {
            Set<ExtendedToricClassKey> expected = new HashSet<>();
            JointRootGenerator.generateResiduals(partition, pair -> assertTrue(expected.add(pair.extendedToricClassKey())));
            Set<ExtendedToricClassKey> actual = ConcurrentHashMap.newKeySet();
            var counts = JointRootGenerator.generateResidualsConcurrently(partition, 4, () -> { },
                    pair -> assertTrue(actual.add(pair.extendedToricClassKey()), "Duplicate parallel class"));
            assertEquals(expected, actual);
            assertEquals(JointRootGenerator.count(partition), counts, "Complete prefix and leaf counts");
        }
    }

    @Test void nineteenCycleMatchesCheckedCountAndAll667UniqueClasses() {
        Set<ExtendedToricClassKey> keys = ConcurrentHashMap.newKeySet();
        LongAdder noFirst = new LongAdder();
        var counts = JointRootGenerator.generateResidualsConcurrently(new int[]{19}, 4, () -> { }, pair -> {
            assertTrue(keys.add(pair.extendedToricClassKey()));
            var analysis = JointReductionAnalysis.analyze(pair);
            assertFalse(analysis.hasFavorablePrefix());
            if (analysis.route() == JointReductionAnalysis.Route.NO_QUALIFYING_FIRST_MOVE) noFirst.increment();
        });
        assertEquals(667, keys.size());
        assertEquals(2, noFirst.sum());
        assertEquals(new JointRootGenerator.Counts(667, 0, 667, 2, 21190934, 667), counts);
    }

    @Test void consumerFailureIsPropagatedAndWorkersStop() {
        int[] partition = {7};
        assertEquals(2, JointRootGenerator.countResiduals(partition));
        var failure = new IllegalStateException("consumer failed");
        var actual = assertThrows(IllegalStateException.class, () ->
                JointRootGenerator.generateResidualsConcurrently(partition, 4, () -> { }, pair -> { throw failure; }));
        assertSame(failure, actual);
    }
}
