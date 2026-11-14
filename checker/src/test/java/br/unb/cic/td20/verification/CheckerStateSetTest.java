package br.unb.cic.td20.verification;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.joint.ErikssonRoute;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;

import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckerStateSetTest {
    @Test
    void deduplicatesByClassAndKeepsBudgetsDistinct(@TempDir final Path directory) {
        final var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var first = new SearchState(ErikssonRoute.ENDPOINT_BUDGET, pair);
        try (final var states = new CheckerStateSet(directory.resolve("states"))) {
            states.add(first);
            states.add(new SearchState(ErikssonRoute.ENDPOINT_BUDGET, pair.canonicalRepresentative()));
            states.add(new SearchState(ErikssonRoute.ROOT_BUDGET, pair));
            assertEquals(2, states.seal().count());
            assertTrue(states.contains(first));
            final var recovered = new ArrayList<SearchState>();
            states.forEachBatch(recovered::addAll);
            assertEquals(2, recovered.size());
            assertEquals(pair.extendedToricClassKey(),
                    recovered.getFirst().pair().extendedToricClassKey());
            assertThrows(IllegalStateException.class, () -> states.add(first));
        }
        assertThrows(IllegalStateException.class,
                () -> new CheckerStateSet(directory.resolve("states")));
    }
}
