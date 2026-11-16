package br.unb.cic.td20.base;

import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.permutation.OneLinePermutation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "td20.gpuSmoke", matches = "true")
class GpuExplorationCapTest {
    private static GPUSortingSearch gpu() {
        GPUSortingSearch.init(new int[]{0}, 2, 32L * 1024 * 1024);
        return GPUSortingSearch.getInstance();
    }

    @Test void allOddZeroAllowanceCannotCloseBeforeItsNecessaryRankIsReached() {
        byte[] omega = new byte[KnownDiameters.ROOT_SYMBOLS];
        for (int i = 0; i < omega.length; i++) omega[i] = (byte) ((i + 1) % omega.length);
        var pair = pair(omega, 3);
        // Rank ten and budget ten leave no zero moves. Every descendant
        // remains all odd, so the current diameter table first permits a
        // closure at rank one, after nine moves. Fixed points count as odd.
        for (int cap : new int[]{6, 8}) {
            var result = gpu().search(beta(pair), new OneLinePermutation(omega), 10, true, 0, cap);
            assertEquals(GPUSortingSearch.Disposition.DEPTH_LIMIT, result.disposition());
            assertTrue(result.moves().isEmpty());
        }
        var closure = gpu().search(beta(pair), new OneLinePermutation(omega), 10, true, 0, 9);
        assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, closure.disposition());
        assertEquals(9, closure.moves().size());
        replay(pair, closure, 10, 9);
        var capped = gpu().search(beta(pair), new OneLinePermutation(omega), 10, false, 0, 9);
        assertEquals(GPUSortingSearch.Disposition.DEPTH_LIMIT, capped.disposition());
        assertTrue(capped.moves().isEmpty());
        var complete = gpu().search(beta(pair), new OneLinePermutation(omega), 10, false, 0, 10);
        assertEquals(GPUSortingSearch.Disposition.COMPLETE, complete.disposition());
        assertEquals(10, complete.moves().size());
        replay(pair, complete, 10, 10);
    }

    @Test void closureAtTheExplorationCapRetainsTheFullBudget() {
        for (int n : new int[]{7, 9}) {
            byte[] omega = new byte[n];
            for (int i = 0; i < n; i++) omega[i] = (byte) ((i + 1) % n);
            var pair = pair(omega, 2);
            int budget = (n - 1) / 2, cap = budget - 1;
            // An increasing odd-length cycle needs cap gain-two moves before
            // its remaining three symbols fit the one unused budget move.
            assertFalse(oracle(omega, beta(pair), budget, cap - 1, true));
            assertTrue(oracle(omega, beta(pair), budget, cap, true));
            var result = gpu().search(beta(pair), new OneLinePermutation(omega), budget, true, 0, cap);
            assertEquals(GPUSortingSearch.Disposition.DIAMETER_CLOSURE, result.disposition());
            assertEquals(cap, result.moves().size());
            replay(pair, result, budget, cap);
        }
    }

    @Test void completeWitnessAtTheCapDoesNotRequireDiameterClosure() {
        byte[] omega = {1, 2, 0, 4, 5, 3, 7, 8, 6};
        var pair = pair(omega, 1);
        assertFalse(oracle(omega, beta(pair), 4, 2, false));
        assertTrue(oracle(omega, beta(pair), 4, 3, false));
        var result = gpu().search(beta(pair), new OneLinePermutation(omega), 4, false, 0, 3);
        assertEquals(GPUSortingSearch.Disposition.COMPLETE, result.disposition());
        assertEquals(3, result.moves().size());
        replay(pair, result, 4, 3);
    }

    @Test void cappedSearchMatchesAnIndependentRestrictedOracleOnSevenToNineSymbols() {
        int successes = 0, misses = 0;
        for (int n = 7; n <= 9; n++) {
            int budget = n == 7 ? 3 : 4;
            for (var pair : samples(n)) {
                byte[] omega = pair.getOmega().getOneLineNotation();
                for (int cap = 2; cap < budget; cap++) {
                    for (boolean closure : new boolean[]{false, true}) {
                        boolean expected = oracle(omega, beta(pair), budget, cap, closure);
                        var result = gpu().search(beta(pair), new OneLinePermutation(omega),
                                budget, closure, 0, cap);
                        boolean actual = result.disposition() == GPUSortingSearch.Disposition.COMPLETE
                                || result.disposition() == GPUSortingSearch.Disposition.DIAMETER_CLOSURE;
                        assertEquals(expected, actual, "n=" + n + ", cap=" + cap
                                + ", closure=" + closure + ", omega=" + Arrays.toString(omega));
                        if (actual) { successes++; replay(pair, result, budget, cap); }
                        else {
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

    private static List<CyclicTargetPair> samples(int n) {
        var random = new Random(180000L + n);
        var result = new ArrayList<CyclicTargetPair>();
        while (result.size() < 12) {
            int[] target = IntStream.range(0, n).toArray();
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), value = target[i];
                target[i] = target[j]; target[j] = value;
            }
            byte[] rho = new byte[n], omega = new byte[n];
            for (int i = 0; i < n; i++) rho[target[i]] = (byte) target[(i + 1) % n];
            boolean fixed = false;
            // beta is the natural cycle, so omega = rho * beta inverse.
            for (int i = 0; i < n; i++) {
                omega[i] = rho[(i + n - 1) % n];
                fixed |= omega[i] == i;
            }
            if (!fixed) result.add(pair(omega, result.size() % n));
        }
        return result;
    }

    private static CyclicTargetPair pair(byte[] omega, int rotation) {
        var cycles = new MulticyclePermutation();
        boolean[] visited = new boolean[omega.length];
        for (int first = 0; first < omega.length; first++) if (!visited[first]) {
            var symbols = new ArrayList<Integer>();
            int current = first;
            do { visited[current] = true; symbols.add(current); current = omega[current]; }
            while (current != first);
            cycles.add(Cycle.of(symbols.stream().mapToInt(Integer::intValue).toArray()));
        }
        int[] beta = IntStream.range(0, omega.length).map(i -> (i + rotation) % omega.length).toArray();
        return CyclicTargetPair.of(cycles, Cycle.of(beta));
    }

    private static byte[] beta(CyclicTargetPair pair) {
        byte[] beta = new byte[pair.getBeta().size()];
        for (int i = 0; i < beta.length; i++) beta[i] = (byte) pair.getBeta().get(i);
        return beta;
    }

    private static void replay(CyclicTargetPair pair, GPUSortingSearch.SearchOutcome result,
                               int budget, int cap) {
        assertTrue(result.moves().size() <= cap);
        byte[] omega = pair.getOmega().getOneLineNotation().clone();
        for (int[] move : result.moves()) {
            int a = move[0], b = move[1], c = move[2];
            assertNotEquals(a, omega[a]); assertNotEquals(b, omega[b]); assertNotEquals(c, omega[c]);
            int[] cycles = cycleIds(omega);
            assertFalse(cycles[a] != cycles[b] && cycles[b] != cycles[c] && cycles[c] != cycles[a]);
            int oldOdd = oddCycles(omega);
            byte oa = omega[a], ob = omega[b], oc = omega[c];
            omega[a] = oc; omega[b] = oa; omega[c] = ob;
            int gain = oddCycles(omega) - oldOdd;
            assertTrue(gain == 0 || gain == 2);
        }
        var moves = result.moves().stream().map(Cycle::of).toList();
        var certificate = result.disposition() == GPUSortingSearch.Disposition.COMPLETE
                ? SortingCertificate.complete(moves) : SortingCertificate.closure(moves);
        CertificateValidator.validate(pair, certificate, budget);
    }

    private record OracleState(long omega, long beta, int used) {}

    private static boolean oracle(byte[] omega, byte[] beta, int budget, int cap, boolean closure) {
        return oracle(omega, beta, 0, budget, cap, closure, new HashSet<>());
    }

    // Enumerate arbitrary positional supports and recompute cycle counts from
    // the resulting permutation. This does not use CUDA's consecutive-support
    // scan, incremental metadata, or terminal-depth optimization.
    private static boolean oracle(byte[] omega, byte[] beta, int used, int budget, int cap,
                                  boolean closure, Set<OracleState> misses) {
        int nonfixed = 0;
        for (int i = 0; i < omega.length; i++) if (omega[i] != i) nonfixed++;
        if (nonfixed == 0 || (closure && KnownDiameters.knownDiameter(nonfixed) <= budget - used))
            return true;
        if (used == cap) return false;
        var key = new OracleState(packed(omega), packed(beta), used);
        if (misses.contains(key)) return false;
        int oldOdd = oddCycles(omega);
        int[] cycles = cycleIds(omega);
        for (int i = 0; i < beta.length - 2; i++)
            for (int j = i + 1; j < beta.length - 1; j++)
                for (int k = j + 1; k < beta.length; k++) {
                    int a = beta[i], b = beta[j], c = beta[k];
                    if (omega[a] == a || omega[b] == b || omega[c] == c) continue;
                    if (cycles[a] != cycles[b] && cycles[b] != cycles[c] && cycles[c] != cycles[a]) continue;
                    byte[] next = omega.clone();
                    next[a] = omega[c]; next[b] = omega[a]; next[c] = omega[b];
                    int gain = oddCycles(next) - oldOdd;
                    if (gain != 0 && gain != 2) continue;
                    byte[] order = beta.clone();
                    System.arraycopy(beta, j, order, i, k - j);
                    System.arraycopy(beta, i, order, i + k - j, j - i);
                    if (oracle(next, order, used + 1, budget, cap, closure, misses)) return true;
                }
        misses.add(key);
        return false;
    }

    private static long packed(byte[] values) {
        long result = 0;
        for (byte value : values) result = (result << 4) | value;
        return result;
    }

    private static int[] cycleIds(byte[] omega) {
        int[] ids = new int[omega.length];
        Arrays.fill(ids, -1);
        for (int first = 0; first < omega.length; first++) if (ids[first] == -1) {
            int current = first;
            do { ids[current] = first; current = omega[current]; } while (current != first);
        }
        return ids;
    }

    private static int oddCycles(byte[] omega) {
        int[] sizes = new int[omega.length];
        for (int id : cycleIds(omega)) sizes[id]++;
        int odd = 0;
        for (int size : sizes) odd += size & 1;
        return odd;
    }
}
