package br.unb.cic.td20.search;

import br.unb.cic.td20.permutation.Cycle;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static br.unb.cic.td20.search.CommonOperations.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonOperationsTest {

    final private Cycle alpha = Cycle.of("0 2 7");
    final private Cycle beta = Cycle.of("0 5 4 3 8 7 6 2 1");

    @Test
    void testApplyTransposition() {
        assertEquals(Cycle.of("0 1 2 3 4 5 6"), applyTranspositionOptimized(Cycle.of("0 4 5 6 1 2 3"), Cycle.of("0 4 1")));
    }

    @Test
    void testCycleIndex() {
        val beta = Cycle.of("0 5 4 3 2 1");
        val c0 = Cycle.of("0 2 4");
        val c1 = Cycle.of("1 3 5");

        val index = CommonOperations.cycleIndex(Arrays.asList(c0, c1), beta);
        assertArrayEquals(new Cycle[] { c0, c1, c0, c1, c0, c1 }, index);
    }

    @Test
    void testAreSymbolsInCyclicOrder() {
        assertTrue(areSymbolsInCyclicOrder(beta.getInverse(), alpha.getSymbols()));
        assertFalse(areSymbolsInCyclicOrder(beta, alpha.getSymbols()));
    }

    @Test
    void testIsOriented() {
        assertTrue(isOriented(beta, alpha.getInverse()));
        assertFalse(isOriented(beta, alpha));
    }

    @Test
    void byteTranspositionMatchesTheCyclicBlockMove() {
        assertArrayEquals(new byte[] { 0, 4, 5, 6, 1, 2, 3 },
                applyTransposition(new byte[] { 0, 1, 2, 3, 4, 5, 6 },
                        1,
                        4,
                        7));
    }
}
