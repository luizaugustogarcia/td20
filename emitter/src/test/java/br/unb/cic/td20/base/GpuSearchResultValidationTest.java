package br.unb.cic.td20.base;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java validation must remain available without loading the CUDA library. */
class GpuSearchResultValidationTest {
    @Test void packedGpuResultsOwnTheirMovesAndRejectMalformedOrUnresolvedWitnesses() {
        int[] moves = {0 | (1 << 5) | (2 << 10)};
        var result = new GPUSortingSearch.PrimitiveSearchOutcome(GPUSortingSearch.Disposition.COMPLETE, moves);
        var equal = new GPUSortingSearch.PrimitiveSearchOutcome(GPUSortingSearch.Disposition.COMPLETE, moves.clone());
        moves[0] = 0;
        result.packedMoves()[0] = 0;
        assertEquals(0 | (1 << 5) | (2 << 10), result.moveAt(0));
        assertEquals(equal, result); assertEquals(equal.hashCode(), result.hashCode());
        for (var kind : new GPUSortingSearch.Disposition[]{GPUSortingSearch.Disposition.RESIDUAL,
                GPUSortingSearch.Disposition.TIMED_OUT, GPUSortingSearch.Disposition.DEPTH_LIMIT}) {
            assertEquals(0, new GPUSortingSearch.PrimitiveSearchOutcome(kind, new int[0]).moveCount());
            assertThrows(IllegalArgumentException.class, () -> new GPUSortingSearch.PrimitiveSearchOutcome(
                    kind, new int[]{0 | (1 << 5) | (2 << 10)}));
        }
        for (int move : new int[]{-1, 1 << 15, 0, 0 | (0 << 5) | (2 << 10)})
            assertThrows(IllegalArgumentException.class, () -> new GPUSortingSearch.PrimitiveSearchOutcome(
                    GPUSortingSearch.Disposition.COMPLETE, new int[]{move}));
    }

    @Test void timeoutIsUnresolvedAndCannotCarryAWitness() {
        assertEquals(GPUSortingSearch.Disposition.TIMED_OUT,
                GPUSortingSearch.SearchOutcome.requireNativeResult(new int[][]{{3}}, 9, 19));
        assertTrue(new GPUSortingSearch.SearchOutcome(GPUSortingSearch.Disposition.TIMED_OUT, List.of()).moves().isEmpty());
        for (int kind : new int[]{0, 3, 4}) {
            assertThrows(IllegalStateException.class, () -> GPUSortingSearch.SearchOutcome.requireNativeResult(
                    new int[][]{{kind}, {0, 1, 2}}, 9, 19));
            assertThrows(IllegalArgumentException.class, () -> new GPUSortingSearch.SearchOutcome(
                    GPUSortingSearch.Disposition.values()[kind], List.of(new int[]{0, 1, 2})));
        }
    }

    @Test void malformedNativeResultsCannotBeCountedAsSuccessfulSearches() {
        for (int[][] result : new int[][][]{null, {}, {null}, {{5}}, {{-1}}, {{1, 2}},
                {{1}, null}, {{1}, {0, 1}}, {{1}, {1, 0, 2}}, {{1}, {0, 1, 19}},
                {{1}, {0, 1, 2}, {0, 1, 2}, {0, 1, 2}}}) {
            assertThrows(IllegalStateException.class, () ->
                    GPUSortingSearch.SearchOutcome.requireNativeResult(result, 2, 19));
        }
        assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE,
                GPUSortingSearch.SearchOutcome.requireNativeResult(new int[][]{{2}, {0, 1, 2}}, 2, 19));
    }

    @Test void timeoutConversionPreservesZeroAndRejectsNegativeOrOverflowedLimits() {
        assertEquals(0, new GPUSortingSearch.SearchLimits(0).maximumNanos());
        assertEquals(100_000_000, new GPUSortingSearch.SearchLimits(100).maximumNanos());
        assertThrows(IllegalArgumentException.class, () -> new GPUSortingSearch.SearchLimits(-1));
        assertThrows(IllegalArgumentException.class, () -> new GPUSortingSearch.SearchLimits(Long.MAX_VALUE));
    }
}
