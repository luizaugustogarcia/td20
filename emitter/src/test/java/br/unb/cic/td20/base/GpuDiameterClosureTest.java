package br.unb.cic.td20.base;

import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.OneLinePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "td20.gpuSmoke", matches = "true")
class GpuDiameterClosureTest {

    private static GPUSortingSearch gpu() {
        GPUSortingSearch.init(new int[] { 0 }, 2, 32L * 1024 * 1024);
        return GPUSortingSearch.getInstance();
    }

    @Test
    void existingFixedPointsAndNewOnesAreBothCounted() {
        final byte[] omega = { 0, 2, 3, 1, 5, 6, 4 };
        final byte[] beta = { 0, 1, 2, 3, 4, 5, 6 };
        val result = gpu().search(beta, new OneLinePermutation(omega), 2, true);
        assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
        assertEquals(1, result.moves().size());
        check(omega, beta, result, 2);
        assertEquals(GPUSortingSearch.Disposition.RESIDUAL,
                gpu().search(beta, new OneLinePermutation(omega), 1, true).disposition());
    }

    @Test
    void firstMoveSeedingReturnsAClosureBeforeSearchingDescendants() {
        final byte[] omega = { 1, 2, 0, 4, 5, 3, 7, 8, 6 }, beta = { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
        val result = gpu().search(beta, new OneLinePermutation(omega), 4, true);
        assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
        assertEquals(1, result.moves().size());
        check(omega, beta, result, 4);

        val complete = gpu().search(beta, new OneLinePermutation(omega), 4, false);
        assertEquals(GPUSortingSearch.Disposition.COMPLETE, complete.disposition());
        assertTrue(complete.moves().size() > 1);
        check(omega, beta, complete, 4);
    }

    @Test
    void descendantSearchReturnsAClosureBeforeExhaustingTheBudget() {
        val permutation = new br.unb.cic.td20.permutation.MulticyclePermutation("(0 1 11 6 8 12 9 3 5 2 10 7 4)");
        final byte[] omega = new byte[13], beta = new byte[13];
        for (int i = 0; i < 13; i++) {
            omega[i] = (byte) permutation.image(i);
            beta[i] = (byte) i;
        }

        val result = gpu().search(beta, new OneLinePermutation(omega), 6, true);
        assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
        assertTrue(result.moves().size() < 6);
        check(omega, beta, result, 6);
    }

    @Test
    void allValidPairsThroughSixSymbolsMatchAnIndependentRestrictedSearch() {
        for (int n = 1; n <= 6; n++) {
            val beta = new byte[n];
            for (int i = 0; i < n; i++) {
                beta[i] = (byte) i;
            }

            permute(beta.clone(),
                    0,
                    omega -> {
                        if (!hasCyclicTarget(omega)) {
                            return;
                        }
                        for (int budget = 1; budget <= 3; budget++) {
                            for (final boolean closure : new boolean[] { false, true }) {
                                val result = gpu().search(beta, new OneLinePermutation(omega.clone()), budget, closure);
                                assertEquals(oracle(omega, beta, 0, budget, closure), result.disposition() != GPUSortingSearch.Disposition.RESIDUAL);
                                check(omega, beta, result, budget);
                            }
                        }
                    });
        }
    }

    @Test
    void largeUnorientedRootClosesWithRotatedBetaAndReplayedMoves() {
        val permutation = new br.unb.cic.td20.permutation.MulticyclePermutation("(0 4 2)(1 5 3)(6 10 8)(7 14 9)(11 17 13)(12 15)(16 18)");
        final byte[] omega = new byte[19], beta = new byte[19];
        for (int i = 0; i < 19; i++) {
            omega[i] = (byte) permutation.image(i);
            beta[i] = (byte) ((i + 2) % 19);
        }

        val result = gpu().search(beta, new OneLinePermutation(omega), 10, true);
        assertNotEquals(GPUSortingSearch.Disposition.RESIDUAL, result.disposition());
        check(omega, beta, result, 10);
    }

    private static void check(final byte[] input, final byte[] beta, final GPUSortingSearch.SearchOutcome result, final int budget) {
        if (result.disposition() == GPUSortingSearch.Disposition.RESIDUAL) {
            assertTrue(result.moves().isEmpty());
            return;
        }

        final byte[] omega = input.clone();
        val moves = new ArrayList<br.unb.cic.td20.permutation.Cycle>();
        for (final int[] move : result.moves()) {
            final int previous = oddCycles(omega);
            final int a = move[0], b = move[1], c = move[2];
            assertNotEquals(a, omega[a]);
            assertNotEquals(b, omega[b]);
            assertNotEquals(c, omega[c]);

            final byte oa = omega[a], ob = omega[b], oc = omega[c];
            omega[a] = oc;
            omega[b] = oa;
            omega[c] = ob;

            final int delta = oddCycles(omega) - previous;
            assertTrue(delta == 0 || delta == 2, "A GPU path contains a forbidden move");
            moves.add(br.unb.cic.td20.permutation.Cycle.of(move));
        }

        val pair = pair(input, beta);
        val record = result.disposition() == GPUSortingSearch.Disposition.COMPLETE ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
        CertificateValidator.validate(pair, record, budget);
    }

    private static CyclicTargetPair pair(final byte[] omega, final byte[] beta) {
        val cycles = new br.unb.cic.td20.permutation.MulticyclePermutation();
        val seen = new boolean[omega.length];
        for (int i = 0; i < omega.length; i++) {
            if (!seen[i]) {
                val symbols = new ArrayList<Integer>();
                int x = i;
                do {
                    seen[x] = true;
                    symbols.add(x);
                    x = omega[x];
                } while (x != i);

                cycles.add(br.unb.cic.td20.permutation.Cycle.of(symbols.stream().mapToInt(Integer::intValue).toArray()));
            }
        }

        val symbols = new int[beta.length];
        for (int i = 0; i < beta.length; i++) {
            symbols[i] = beta[i];
        }

        return CyclicTargetPair.of(cycles, br.unb.cic.td20.permutation.Cycle.of(symbols));
    }

    private static boolean oracle(final byte[] omega, final byte[] beta, final int used, final int budget, final boolean closure) {
        int nonfixed = 0;
        for (int i = 0; i < omega.length; i++) {
            if (omega[i] != i) {
                nonfixed++;
            }
        }
        if (nonfixed == 0 || (closure && KnownDiameters.knownDiameter(nonfixed) <= budget - used)) {
            return true;
        }
        if (used == budget) {
            return false;
        }

        final int odd = oddCycles(omega);
        for (int i = 0; i < beta.length - 2; i++) {
            for (int j = i + 1; j < beta.length - 1; j++) {
                for (int k = j + 1; k < beta.length; k++) {
                    final int a = beta[i], b = beta[j], c = beta[k];
                    if (omega[a] == a || omega[b] == b || omega[c] == c) {
                        continue;
                    }

                    final byte[] next = omega.clone();
                    next[a] = omega[c];
                    next[b] = omega[a];
                    next[c] = omega[b];

                    final int gain = oddCycles(next) - odd;
                    if (gain != 0 && gain != 2) {
                        continue;
                    }

                    final byte[] order = beta.clone();
                    System.arraycopy(beta, j, order, i, k - j);
                    System.arraycopy(beta, i, order, i + k - j, j - i);
                    if (oracle(next, order, used + 1, budget, closure)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static int oddCycles(final byte[] omega) {
        val seen = new boolean[omega.length];
        int odd = 0;
        for (int i = 0; i < omega.length; i++) {
            if (!seen[i]) {
                int x = i, length = 0;
                do {
                    seen[x] = true;
                    length++;
                    x = omega[x];
                } while (x != i);

                odd += length % 2;
            }
        }

        return odd;
    }

    private static boolean hasCyclicTarget(final byte[] omega) {
        int x = 0, count = 0;
        do {
            x = omega[(x + 1) % omega.length];
            count++;
        } while (x != 0 && count <= omega.length);

        return count == omega.length;
    }

    private static void permute(final byte[] values, final int index, final java.util.function.Consumer<byte[]> sink) {
        if (index == values.length) {
            sink.accept(values.clone());
            return;
        }
        for (int i = index; i < values.length; i++) {
            byte value = values[i];
            values[i] = values[index];
            values[index] = value;
            permute(values, index + 1, sink);
            value = values[i];
            values[i] = values[index];
            values[index] = value;
        }
    }
}
