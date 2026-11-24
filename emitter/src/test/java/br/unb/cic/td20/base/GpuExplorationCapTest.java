package br.unb.cic.td20.base;

import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.permutation.OneLinePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "td20.gpuSmoke", matches = "true")
class GpuExplorationCapTest {

    private static GPUSortingSearch gpu() {
        GPUSortingSearch.init(new int[] { 0 }, 2, 32L * 1024 * 1024);
        return GPUSortingSearch.getInstance();
    }

    @Test
    void allOddZeroAllowanceCannotCloseBeforeItsNecessaryRankIsReached() {
        val omega = new byte[KnownDiameters.ROOT_SYMBOLS];
        for (int i = 0; i < omega.length; i++) {
            omega[i] = (byte) ((i + 1) % omega.length);
        }

        val pair = pair(omega, 3);
        // Rank ten and budget ten leave no zero moves. Every descendant
        // remains all odd, so the current diameter table first permits a
        // closure at rank one, after nine moves. Fixed points count as odd.
        for (final int cap : new int[] { 6, 8 }) {
            val result = gpu().search(beta(pair), new OneLinePermutation(omega), 10, true, 0, cap);
            assertEquals(GPUSortingSearch.Disposition.DEPTH_LIMIT, result.disposition());
            assertTrue(result.moves().isEmpty());
        }

        val closure = gpu().search(beta(pair), new OneLinePermutation(omega), 10, true, 0, 9);
        assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, closure.disposition());
        assertEquals(9, closure.moves().size());
        replay(pair, closure, 10, 9);

        val capped = gpu().search(beta(pair), new OneLinePermutation(omega), 10, false, 0, 9);
        assertEquals(GPUSortingSearch.Disposition.DEPTH_LIMIT, capped.disposition());
        assertTrue(capped.moves().isEmpty());

        val complete = gpu().search(beta(pair), new OneLinePermutation(omega), 10, false, 0, 10);
        assertEquals(GPUSortingSearch.Disposition.COMPLETE, complete.disposition());
        assertEquals(10, complete.moves().size());
        replay(pair, complete, 10, 10);
    }

    @Test
    void closureAtTheExplorationCapRetainsTheFullBudget() {
        for (final int n : new int[] { 7, 9 }) {
            val omega = new byte[n];
            for (int i = 0; i < n; i++) {
                omega[i] = (byte) ((i + 1) % n);
            }

            val pair = pair(omega, 2);
            final int budget = (n - 1) / 2, cap = budget - 1;
            // An increasing odd-length cycle needs cap gain-two moves before
            // its remaining three symbols fit the one unused budget move.
            assertFalse(oracle(omega, beta(pair), budget, cap - 1, true));
            assertTrue(oracle(omega, beta(pair), budget, cap, true));

            val result = gpu().search(beta(pair), new OneLinePermutation(omega), budget, true, 0, cap);
            assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
            assertEquals(cap, result.moves().size());
            replay(pair, result, budget, cap);
        }
    }

    @Test
    void completeWitnessAtTheCapDoesNotRequireDiameterClosure() {
        final byte[] omega = { 1, 2, 0, 4, 5, 3, 7, 8, 6 };
        val pair = pair(omega, 1);
        assertFalse(oracle(omega, beta(pair), 4, 2, false));
        assertTrue(oracle(omega, beta(pair), 4, 3, false));

        val result = gpu().search(beta(pair), new OneLinePermutation(omega), 4, false, 0, 3);
        assertEquals(GPUSortingSearch.Disposition.COMPLETE, result.disposition());
        assertEquals(3, result.moves().size());
        replay(pair, result, 4, 3);
    }

    @Test
    void cappedSearchMatchesAnIndependentRestrictedOracleOnSevenToNineSymbols() {
        int successes = 0, misses = 0;
        for (int n = 7; n <= 9; n++) {
            final int budget = n == 7 ? 3 : 4;
            for (val pair : samples(n)) {
                final byte[] omega = pair.getOmega().getOneLineNotation();
                for (int cap = 2; cap < budget; cap++) {
                    for (final boolean closure : new boolean[] { false, true }) {
                        final boolean expected = oracle(omega, beta(pair), budget, cap, closure);
                        val result = gpu().search(beta(pair),
                                new OneLinePermutation(omega),
                                budget,
                                closure,
                                0,
                                cap);
                        final boolean actual = result.disposition() == GPUSortingSearch.Disposition.COMPLETE
                                || result.disposition() == GPUSortingSearch.Disposition.DIAMETER_CLOSURE;
                        assertEquals(expected,
                                actual,
                                "n=" + n + ", cap=" + cap + ", closure=" + closure + ", omega=" + Arrays.toString(omega));
                        if (actual) {
                            successes++;
                            replay(pair, result, budget, cap);
                        } else {
                            misses++;
                            assertEquals(GPUSortingSearch.Disposition.DEPTH_LIMIT, result.disposition());
                            assertTrue(result.moves().isEmpty());
                        }
                    }
                }
            }
        }

        assertTrue(successes > 0, "The sample must exercise successful capped searches");
        assertTrue(misses > 0, "The sample must exercise unresolved capped searches");
    }

    private static List<CyclicTargetPair> samples(final int n) {
        val random = new Random(180000L + n);
        val result = new ArrayList<CyclicTargetPair>();
        while (result.size() < 12) {
            final int[] target = IntStream.range(0, n).toArray();
            for (int i = n - 1; i > 0; i--) {
                final int j = random.nextInt(i + 1), value = target[i];
                target[i] = target[j];
                target[j] = value;
            }

            final byte[] rho = new byte[n], omega = new byte[n];
            for (int i = 0; i < n; i++) {
                rho[target[i]] = (byte) target[(i + 1) % n];
            }

            boolean fixed = false;
            // beta is the natural cycle, so omega = rho * beta inverse.
            for (int i = 0; i < n; i++) {
                omega[i] = rho[(i + n - 1) % n];
                fixed |= omega[i] == i;
            }
            if (!fixed) {
                result.add(pair(omega, result.size() % n));
            }
        }

        return result;
    }

    private static CyclicTargetPair pair(final byte[] omega, final int rotation) {
        val cycles = new MulticyclePermutation();
        val visited = new boolean[omega.length];
        for (int first = 0; first < omega.length; first++) {
            if (!visited[first]) {
                val symbols = new ArrayList<Integer>();
                int current = first;
                do {
                    visited[current] = true;
                    symbols.add(current);
                    current = omega[current];
                } while (current != first);

                cycles.add(Cycle.of(symbols.stream().mapToInt(Integer::intValue).toArray()));
            }
        }

        final int[] beta = IntStream.range(0, omega.length).map(i -> (i + rotation) % omega.length).toArray();

        return CyclicTargetPair.of(cycles, Cycle.of(beta));
    }

    private static byte[] beta(final CyclicTargetPair pair) {
        val beta = new byte[pair.getBeta().size()];
        for (int i = 0; i < beta.length; i++) {
            beta[i] = (byte) pair.getBeta().get(i);
        }

        return beta;
    }

    private static void replay(final CyclicTargetPair pair, final GPUSortingSearch.SearchOutcome result, final int budget, final int cap) {
        assertTrue(result.moves().size() <= cap);

        final byte[] omega = pair.getOmega().getOneLineNotation().clone();
        for (final int[] move : result.moves()) {
            final int a = move[0], b = move[1], c = move[2];
            assertNotEquals(a, omega[a]);
            assertNotEquals(b, omega[b]);
            assertNotEquals(c, omega[c]);

            final int[] cycles = cycleIds(omega);
            assertFalse(cycles[a] != cycles[b] && cycles[b] != cycles[c] && cycles[c] != cycles[a]);

            final int oldOdd = oddCycles(omega);
            final byte oa = omega[a], ob = omega[b], oc = omega[c];
            omega[a] = oc;
            omega[b] = oa;
            omega[c] = ob;

            final int gain = oddCycles(omega) - oldOdd;
            assertTrue(gain == 0 || gain == 2);
        }

        val moves = result.moves().stream().map(Cycle::of).toList();
        val certificate = result.disposition() == GPUSortingSearch.Disposition.COMPLETE ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
        CertificateValidator.validate(pair, certificate, budget);
    }

    private record OracleState(long omega, long beta, int used) {
    }

    private static boolean oracle(final byte[] omega, final byte[] beta, final int budget, final int cap, final boolean closure) {
        return oracle(omega, beta, 0, budget, cap, closure, new HashSet<>());
    }

    // Enumerate arbitrary positional supports and recompute cycle counts from
    // the resulting permutation. This does not use CUDA's consecutive-support
    // scan, incremental metadata, or terminal-depth optimization.
    private static boolean oracle(final byte[] omega,
            final byte[] beta,
            final int used,
            final int budget,
            final int cap,
            final boolean closure,
            final Set<OracleState> misses) {
        int nonfixed = 0;
        for (int i = 0; i < omega.length; i++) {
            if (omega[i] != i) {
                nonfixed++;
            }
        }
        if (nonfixed == 0 || (closure && KnownDiameters.knownDiameter(nonfixed) <= budget - used)) {
            return true;
        }
        if (used == cap) {
            return false;
        }

        val key = new OracleState(packed(omega), packed(beta), used);
        if (misses.contains(key)) {
            return false;
        }

        final int oldOdd = oddCycles(omega);
        final int[] cycles = cycleIds(omega);
        for (int i = 0; i < beta.length - 2; i++) {
            for (int j = i + 1; j < beta.length - 1; j++) {
                for (int k = j + 1; k < beta.length; k++) {
                    final int a = beta[i], b = beta[j], c = beta[k];
                    if (omega[a] == a || omega[b] == b || omega[c] == c) {
                        continue;
                    }
                    if (cycles[a] != cycles[b] && cycles[b] != cycles[c] && cycles[c] != cycles[a]) {
                        continue;
                    }

                    final byte[] next = omega.clone();
                    next[a] = omega[c];
                    next[b] = omega[a];
                    next[c] = omega[b];

                    final int gain = oddCycles(next) - oldOdd;
                    if (gain != 0 && gain != 2) {
                        continue;
                    }

                    final byte[] order = beta.clone();
                    System.arraycopy(beta, j, order, i, k - j);
                    System.arraycopy(beta, i, order, i + k - j, j - i);
                    if (oracle(next, order, used + 1, budget, cap, closure, misses)) {
                        return true;
                    }
                }
            }
        }

        misses.add(key);

        return false;
    }

    private static long packed(final byte[] values) {
        long result = 0;
        for (final byte value : values) {
            result = (result << 4) | value;
        }

        return result;
    }

    private static int[] cycleIds(final byte[] omega) {
        val ids = new int[omega.length];
        Arrays.fill(ids, -1);
        for (int first = 0; first < omega.length; first++) {
            if (ids[first] == -1) {
                int current = first;
                do {
                    ids[current] = first;
                    current = omega[current];
                } while (current != first);
            }
        }

        return ids;
    }

    private static int oddCycles(final byte[] omega) {
        val sizes = new int[omega.length];
        for (final int id : cycleIds(omega)) {
            sizes[id]++;
        }

        int odd = 0;
        for (final int size : sizes) {
            odd += size & 1;
        }

        return odd;
    }
}
