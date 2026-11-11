package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.reduction.Partitions;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JointParallelGenerationTest {
    @Test void parallelFrontierPreservesEveryClassAndEveryCountThroughNineSymbols() {
        for (int n = 3; n <= 9; n++) for (int[] partition : Partitions.generateValidPartitions(n)) {
            Set<ExtendedToricClassKey> expected = new HashSet<>();
            JointRootGenerator.generateResiduals(partition, pair -> assertTrue(expected.add(pair.extendedToricClassKey())));
            Set<ExtendedToricClassKey> actual = ConcurrentHashMap.newKeySet();
            var counts = JointRootGenerator.generateResidualsConcurrently(partition, () -> { },
                    pair -> assertTrue(actual.add(pair.extendedToricClassKey()), "Duplicate parallel class"));
            assertEquals(expected, actual);
            assertEquals(JointRootGenerator.count(partition), counts, "Complete prefix and leaf counts");
        }
    }

    @Test void consumerFailureIsPropagatedAndWorkersStop() {
        int[] partition = {7};
        assertEquals(2, JointRootGenerator.countResiduals(partition));
        var failure = new IllegalStateException("consumer failed");
        var actual = assertThrows(IllegalStateException.class, () ->
                JointRootGenerator.generateResidualsConcurrently(partition, () -> { }, pair -> { throw failure; }));
        assertSame(failure, actual);
    }
}
