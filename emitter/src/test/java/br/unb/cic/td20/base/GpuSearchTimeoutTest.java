package br.unb.cic.td20.base;

import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "td20.gpuSmoke", matches = "true")
class GpuSearchTimeoutTest {

    // A single move on this 13-cycle fixes at most two symbols. Its remainder
    // therefore needs at least six moves, more than the five still available.
    // It cannot close in seeding; the unrestricted search closes deeper.
    private static CyclicTargetPair deeperClosure() {
        return CyclicTargetPair.of(new MulticyclePermutation("(0 1 11 6 8 12 9 3 5 2 10 7 4)"),
                Cycle.of(IntStream.range(0, 13).toArray()));
    }

    @Test
    void expiredSearchIsUnresolvedAndTheSameSlotCanThenFinish() throws Exception {
        val pair = deeperClosure();
        val timeout = rawSearch(pair, 6, 1L);
        assertEquals(3, timeout[0][0]);
        assertEquals(1, timeout.length, "A timeout cannot return an unproved prefix");

        val result = rawSearch(pair, 6, 0L);
        assertEquals(2, result[0][0]);
        replay(pair, result, 6);
    }

    @Test
    void seededWitnessWinsOverAnExpiredAllowance() throws Exception {
        val pair = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),
                Cycle.of(0, 1, 2, 3, 4, 5));
        val result = rawSearch(pair, 2, 1L);
        assertEquals(2, result[0][0]);
        assertEquals(2, result.length);
        replay(pair, result, 2);
    }

    @Test
    void cappedSearchIsUnresolvedAndTheSameSlotCanThenFinish() throws Exception {
        val pair = deeperClosure();
        val capped = rawSearch(pair, 6, 0L, 1);
        assertArrayEquals(new int[] { 4 }, capped[0]);
        assertEquals(1, capped.length);

        val depthFive = rawSearch(pair, 6, 0L, 5);
        assertTrue(depthFive.length - 1 <= 5);
        if (depthFive[0][0] == 2) {
            replay(pair, depthFive, 6);
        } else {
            assertArrayEquals(new int[] { 4 }, depthFive[0]);
        }

        val full = rawSearch(pair, 6, 0L);
        assertEquals(2, full[0][0]);
        replay(pair, full, 6);
    }

    @Test
    void closureAtTheDepthCapUsesTheFullObligationBudget() throws Exception {
        val pair = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)(3 4 5)"),
                Cycle.of(0, 1, 2, 3, 4, 5));
        val result = rawSearch(pair, 2, 0L, 1);
        assertEquals(2, result[0][0]);
        assertEquals(2, result.length);
        replay(pair, result, 2);
    }

    private static int[][] rawSearch(final CyclicTargetPair pair, final int budget, final long nanos) throws Exception {
        return rawSearch(pair, budget, nanos, budget);
    }

    private static int[][] rawSearch(final CyclicTargetPair pair, final int budget, final long nanos, final int depth) throws Exception {
        GPUSortingSearch.init(new int[] { 0 }, 2, 32L * 1024 * 1024);

        // Nanosecond precision makes cancellation deterministic without a
        // hardware-dependent slow fixture. Normal callers configure milliseconds.
        final Method method = GPUSortingSearch.class.getDeclaredMethod("searchForSortingSeqWithLimits",
                byte[].class,
                byte[].class,
                int.class,
                boolean.class,
                int.class,
                long.class,
                int[].class,
                int.class);
        method.setAccessible(true);

        val beta = new byte[pair.getBeta().size()];
        for (int i = 0; i < beta.length; i++) {
            beta[i] = (byte) pair.getBeta().get(i);
        }

        final int[] diameters = IntStream.rangeClosed(0, KnownDiameters.ROOT_SYMBOLS).map(KnownDiameters::knownDiameter).toArray();

        return (int[][]) method.invoke(null, beta, pair.getOmega().getOneLineNotation(), budget, true, depth, nanos, diameters, 0);
    }

    private static void replay(final CyclicTargetPair pair, final int[][] result, final int budget) {
        final int[] beta = pair.getBeta().getSymbols().clone();
        val moves = new ArrayList<Cycle>();
        for (int step = 1; step < result.length; step++) {
            final int i = result[step][0], j = result[step][1], k = result[step][2];
            moves.add(Cycle.of(beta[i], beta[j], beta[k]));

            final int[] copy = beta.clone();
            System.arraycopy(copy, j, beta, i, k - j);
            System.arraycopy(copy, i, beta, i + k - j, j - i);
        }

        CertificateValidator.validate(pair, SortingCertificate.closure(moves), budget);
    }
}
