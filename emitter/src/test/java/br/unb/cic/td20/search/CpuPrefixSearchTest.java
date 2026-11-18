package br.unb.cic.td20.search;

import br.unb.cic.td20.bracelet.DirectTwistedBraceletGenerator;
import br.unb.cic.td20.certificate.CertificateValidator;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuPrefixSearchTest {
    @Test void allValidPairsThroughSixSymbolsMatchIndependentRestrictedSearch() {
        for (int n = 1; n <= 6; n++) {
            int[] beta = new int[n]; Arrays.setAll(beta, i -> i);
            permute(beta.clone(), 0, omega -> {
                if (!hasCyclicTarget(omega)) return;
                var input = pair(omega, beta);
                for (int budget = 1; budget <= 3; budget++) {
                    var result = CpuPrefixSearch.search(input, budget, budget, Integer.MAX_VALUE);
                    assertEquals(oracle(omega, beta, 0, budget), result.isPresent(),
                            "omega=" + Arrays.toString(omega) + " budget=" + budget);
                    var primitive = CpuPrefixSearch.search(PrimitivePair.of(omega, beta), budget, budget, Integer.MAX_VALUE);
                    assertEquals(oracle(omega, beta, 0, budget), primitive.isPresent(),
                            "primitive omega=" + Arrays.toString(omega) + " budget=" + budget);
                    if (primitive.isPresent()) {
                        var packed = primitive.orElseThrow();
                        var legacy = result.orElseThrow();
                        assertEquals(legacy.kind().name(), packed.kind().name());
                        assertEquals(legacy.moves().size(), packed.moveCount());
                        for (int step = 0; step < packed.moveCount(); step++) {
                            var move = legacy.moves().get(step);
                            assertEquals(move.get(0) | (move.get(1) << 5) | (move.get(2) << 10), packed.moveAt(step));
                        }
                        CertificateValidator.validate(PrimitivePair.of(omega, beta), packed, budget);
                    }
                    result.ifPresent(record -> check(input, record));
                    if (result.isPresent()) CertificateValidator.validate(input, result.orElseThrow(), budget);
                }
            });
        }
    }

    @Test void fixedPointsAreCountedAtDepthZeroAndAfterMoves() {
        int[] omega = {0, 2, 3, 1, 5, 6, 4}, beta = {0, 1, 2, 3, 4, 5, 6};
        var input = pair(omega, beta);
        var result = CpuPrefixSearch.search(input, 2, 3, 8192).orElseThrow();
        assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, result.kind());
        assertEquals(1, result.moves().size());
        CertificateValidator.validate(input, result, 2);
        assertTrue(CpuPrefixSearch.search(input, 1, 3, 8192).isEmpty());
        var initial = CpuPrefixSearch.search(input, 4, 0, 0).orElseThrow();
        assertTrue(initial.moves().isEmpty());
        CertificateValidator.validate(input, initial, 4);
        var identity = CpuPrefixSearch.search(pair(beta, beta), 1, 0, 0).orElseThrow();
        assertEquals(SortingCertificate.Kind.COMPLETE, identity.kind());
        assertTrue(identity.moves().isEmpty());
    }

    @Test void primitiveWorkspaceKeepsAnchorsAndInputsAcrossLargeThenSmallDomains() {
        var input = CyclicTargetPair.of(new MulticyclePermutation(
                "(0)(1 5 9 13)(2 7 10 8)(14 11 3)(12 6 4)"),
                Cycle.of(java.util.stream.IntStream.range(0, 15).toArray()).startingBy(2));
        int[] omega = new int[15]; Arrays.setAll(omega, input.getOmega()::image);
        var pair = PrimitivePair.of(omega, input.getBeta().getSymbols());
        var beforeOmega = pair.omegaBytes(); var beforeBeta = pair.betaBytes();
        var result = CpuPrefixSearch.search(pair, 7, 1, 1).orElseThrow();
        assertEquals(1, result.moveCount());
        assertEquals(5 | (9 << 5) | (1 << 10), result.moveAt(0));
        CertificateValidator.validate(pair, result, 7);
        assertArrayEquals(beforeOmega, pair.omegaBytes()); assertArrayEquals(beforeBeta, pair.betaBytes());
        assertTrue(CpuPrefixSearch.search(pair, 7, 0, 8192).isEmpty());
        assertTrue(CpuPrefixSearch.searchWithinNanos(pair, 7, 3, 0).isEmpty());
        var identity = PrimitivePair.of(new int[]{0}, new int[]{0});
        assertEquals(br.unb.cic.td20.certificate.PrimitiveCertificate.Kind.COMPLETE,
                CpuPrefixSearch.search(identity, 1, 0, 0).orElseThrow().kind());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> CpuPrefixSearch.search(pair, 7, 3, 8192));
        } finally { Thread.interrupted(); }
        assertArrayEquals(beforeOmega, pair.omegaBytes()); assertArrayEquals(beforeBeta, pair.betaBytes());
        assertEquals(result, CpuPrefixSearch.search(pair, 7, 1, 1).orElseThrow());
    }

    @Test void boundsProduceMissesAndLeaveTheOriginalObligationUntouched() {
        var input = pair(new int[]{1, 2, 0, 4, 5, 3}, new int[]{0, 1, 2, 3, 4, 5});
        var before = input.toString();
        assertTrue(CpuPrefixSearch.search(input, 2, 0, 8192).isEmpty());
        assertTrue(CpuPrefixSearch.search(input, 2, 3, 0).isEmpty());
        var first = CpuPrefixSearch.search(input, 2, 3, 8192).orElseThrow();
        var second = CpuPrefixSearch.search(input, 2, 3, 8192).orElseThrow();
        assertEquals(first, second);
        assertEquals(before, input.toString());
        assertThrows(IllegalArgumentException.class, () -> CpuPrefixSearch.search(input, 0, 3, 8192));
        assertThrows(IllegalArgumentException.class, () -> CpuPrefixSearch.search(input, 2, -1, 8192));
        assertThrows(IllegalArgumentException.class, () -> CpuPrefixSearch.search(input, 2, 3, -1));
    }

    @Test void totallyUnorientedStatesUseActualSortingPrefixes() {
        var closed = new AtomicInteger();
        DirectTwistedBraceletGenerator.generateRepresentatives(new int[]{3, 2, 2}, new boolean[3], input -> {
            var result = CpuPrefixSearch.search(input, 3, 3, Integer.MAX_VALUE);
            if (result.isPresent()) {
                assertFalse(result.orElseThrow().moves().isEmpty());
                check(input, result.orElseThrow());
                CertificateValidator.validate(input, result.orElseThrow(), 3);
                closed.incrementAndGet();
            }
        });
        assertTrue(closed.get() > 0);
    }

    @Test void closureIsCheckedAtTheThirdDepthOfATotallyUnorientedPrefix() {
        var input = CyclicTargetPair.of(new MulticyclePermutation("(0 5 2)(1 7 3)(4 8 6)"),
                Cycle.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
        assertTrue(CpuPrefixSearch.search(input, 4, 2, 8192).isEmpty());
        var result = CpuPrefixSearch.search(input, 4, 3, 8192).orElseThrow();
        assertEquals(3, result.moves().size());
        assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, result.kind());
        check(input, result);
        CertificateValidator.validate(input, result, 4);
    }

    @Test void consecutiveEvenLengthSupportClosesAfterVirtualContractionAcrossTheOrigin() {
        int[] order = java.util.stream.IntStream.range(0, 15).toArray();
        var input = CyclicTargetPair.of(new MulticyclePermutation(
                "(0)(1 5 9 13)(2 7 10 8)(14 11 3)(12 6 4)"), Cycle.of(order).startingBy(2));
        // Fourteen nonfixed symbols need eight moves initially. The first
        // consecutive support creates two fixed points, leaving TD(11)=6.
        var result = CpuPrefixSearch.search(input, 7, 1, 1).orElseThrow();
        assertEquals(List.of(Cycle.of(5, 9, 1)), result.moves());
        assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, result.kind());
        check(input, result);
        CertificateValidator.validate(input, result, 7);
    }

    @Test void positiveGainPrefixesReachClosureBeforeTheCubicScanConsumesTheBudget() {
        var input = CyclicTargetPair.of(new MulticyclePermutation(
                "(0 4 2)(1 5 3)(6 10 8)(7 14 9)(11 17 13)(12 15)(16 18)"),
                Cycle.of(java.util.stream.IntStream.range(0, 19).toArray()));
        assertTrue(br.unb.cic.td20.joint.CyclicTargetMoves.isTotallyUnoriented(
                java.util.stream.IntStream.range(0, 19).map(input.getOmega()::image).toArray(),
                input.getBeta().getSymbols()));
        var result = CpuPrefixSearch.search(input, 9, 3, 8192).orElseThrow();
        assertEquals(3, result.moves().size());
        assertEquals(SortingCertificate.Kind.DIAMETER_CLOSURE, result.kind());
        check(input, result);
        CertificateValidator.validate(input, result, 9);
    }

    private static void check(CyclicTargetPair input, SortingCertificate result) {
        int[] omega = new int[input.getBeta().size()];
        Arrays.setAll(omega, input.getOmega()::image);
        Cycle beta = input.getBeta();
        for (var move : result.moves()) {
            int a = move.get(0), b = move.get(1), c = move.get(2);
            assertTrue(beta.indexOf(a) < beta.indexOf(b) && beta.indexOf(b) < beta.indexOf(c));
            assertNotEquals(a, omega[a]); assertNotEquals(b, omega[b]); assertNotEquals(c, omega[c]);
            int oldOdd = oddCycles(omega);
            int oa = omega[a], ob = omega[b], oc = omega[c];
            omega[a] = oc; omega[b] = oa; omega[c] = ob;
            int delta = oddCycles(omega) - oldOdd;
            assertTrue(delta == 0 || delta == 2);
            beta = CommonOperations.applyTranspositionOptimized(beta, move);
        }
    }

    private static boolean oracle(int[] omega, int[] beta, int used, int budget) {
        int nonfixed = 0; for (int symbol = 0; symbol < omega.length; symbol++) if (omega[symbol] != symbol) nonfixed++;
        if (KnownDiameters.knownDiameter(nonfixed) <= budget - used) return true;
        if (used == budget) return false;
        int odd = oddCycles(omega);
        for (int i = 0; i < beta.length - 2; i++) for (int j = i + 1; j < beta.length - 1; j++) for (int k = j + 1; k < beta.length; k++) {
            int a = beta[i], b = beta[j], c = beta[k];
            if (omega[a] == a || omega[b] == b || omega[c] == c) continue;
            int[] next = omega.clone(); next[a] = omega[c]; next[b] = omega[a]; next[c] = omega[b];
            int delta = oddCycles(next) - odd; if (delta != 0 && delta != 2) continue;
            int[] order = beta.clone();
            System.arraycopy(beta, j, order, i, k - j); System.arraycopy(beta, i, order, i + k - j, j - i);
            if (oracle(next, order, used + 1, budget)) return true;
        }
        return false;
    }

    private static int oddCycles(int[] omega) {
        boolean[] seen = new boolean[omega.length]; int odd = 0;
        for (int symbol = 0; symbol < omega.length; symbol++) if (!seen[symbol]) {
            int current = symbol, length = 0;
            do { seen[current] = true; length++; current = omega[current]; } while (current != symbol);
            odd += length & 1;
        }
        return odd;
    }

    private static CyclicTargetPair pair(int[] omega, int[] beta) {
        var cycles = new MulticyclePermutation(); boolean[] seen = new boolean[omega.length];
        for (int symbol = 0; symbol < omega.length; symbol++) if (!seen[symbol]) {
            var symbols = new ArrayList<Integer>(); int current = symbol;
            do { seen[current] = true; symbols.add(current); current = omega[current]; } while (current != symbol);
            cycles.add(Cycle.of(symbols.stream().mapToInt(Integer::intValue).toArray()));
        }
        return CyclicTargetPair.of(cycles, Cycle.of(beta));
    }

    private static boolean hasCyclicTarget(int[] omega) {
        int current = 0, count = 0;
        do { current = omega[(current + 1) % omega.length]; count++; } while (current != 0 && count <= omega.length);
        return count == omega.length;
    }

    private static void permute(int[] values, int index, Consumer<int[]> sink) {
        if (index == values.length) { sink.accept(values.clone()); return; }
        for (int next = index; next < values.length; next++) {
            int value = values[next]; values[next] = values[index]; values[index] = value;
            permute(values, index + 1, sink);
            value = values[next]; values[next] = values[index]; values[index] = value;
        }
    }
}
