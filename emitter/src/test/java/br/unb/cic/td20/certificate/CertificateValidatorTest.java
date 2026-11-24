package br.unb.cic.td20.certificate;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.permutation.PermutationGroups;
import br.unb.cic.td20.search.CommonOperations;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CertificateValidatorTest {

    @Test
    void primitiveReplayMatchesObjectReferenceOnAllSmallTargetsAndTwoMovePrefixes() {
        val checked = new int[3];
        for (int n = 1; n <= 5; n++) {
            final int domain = n;
            final int[] order = IntStream.range(0, n).toArray();
            val betas = new ArrayList<Cycle>();
            betas.add(Cycle.of(order.clone()));
            if (n > 1) {
                betas.add(Cycle.of(order.clone()).startingBy(1));
                betas.add(Cycle.of(order.clone()).getInverse());
            }

            // Every full target cycle starts at zero; omega is derived independently.
            permute(order,
                    1,
                    symbols -> {
                        val rho = Cycle.of(symbols.clone());
                        for (val beta : betas) {
                            val omega = PermutationGroups.computeProduct(true, domain, rho, beta.getInverse());
                            val pair = CyclicTargetPair.of(omega, beta);
                            final int[] originalBeta = pair.getBeta().getSymbols().clone();
                            final int[] originalOmega = images(pair.getOmega(), domain);
                            comparePrefixes(pair, pair.getBeta(), List.of(), 2, checked);
                            assertArrayEquals(originalBeta, pair.getBeta().getSymbols());
                            assertArrayEquals(originalOmega, images(pair.getOmega(), domain));
                        }
                    });
        }

        assertTrue(checked[0] > 10_000, "The comparison must cover thousands of certificates");
        assertTrue(checked[1] > 0, "Complete witnesses must be accepted");
        assertTrue(checked[2] > 0, "Incomplete complete witnesses must be rejected");
    }

    @Test
    void rejectsMalformedAndLaterUnorderedSupports() {
        val pair = CyclicTargetPair.of(new MulticyclePermutation("(0 1 2)"), Cycle.of(0, 1, 2));
        for (val move : List.of(Cycle.of(0, 1),
                Cycle.of(0, 1, 1),
                Cycle.of(0, 2, 1),
                Cycle.of(1, 2, 0),
                Cycle.of(0, 1, 3))) {
            assertThrows(IllegalArgumentException.class,
                    () -> CertificateValidator.requiredBudget(pair, SortingCertificate.closure(List.of(move))));
        }

        val laterUnordered = SortingCertificate.closure(List.of(Cycle.of(0, 1, 2), Cycle.of(0, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> referenceRequiredBudget(pair, laterUnordered));
        assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(pair, laterUnordered));
    }

    @Test
    void checksTheStoredTargetAfterEveryMove() {
        // Deliberately violate the trusted factory's target premise to exercise replay validation.
        val inconsistent = CyclicTargetPair.ofKnownTargetOwned(new MulticyclePermutation("(0 1 2)"), Cycle.of(0, 1, 2), Cycle.of(0, 1, 2));
        val record = SortingCertificate.complete(List.of(Cycle.of(0, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> referenceRequiredBudget(inconsistent, record));
        assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(inconsistent, record));
    }

    @Test
    void usesActualNonfixedSymbolsIncludingOmittedFixedPoints() {
        val pair = CyclicTargetPair.of(new MulticyclePermutation("(1 2 3)"),
                Cycle.of(0, 1, 2, 3, 4, 5, 6));
        assertEquals(1, CertificateValidator.requiredBudget(pair, SortingCertificate.closure(List.of())));

        val sorted = SortingCertificate.complete(List.of(Cycle.of(1, 2, 3)));
        assertEquals(1, CertificateValidator.requiredBudget(pair, sorted));
        assertEquals(1, referenceRequiredBudget(pair, sorted));
        CertificateValidator.validate(pair, sorted, 1);
        assertThrows(IllegalArgumentException.class, () -> CertificateValidator.validate(pair, sorted, 0));

        final int[] symbols = IntStream.range(0, 21).toArray();
        val fullRoot = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(symbols)), Cycle.of(symbols));
        val unchanged = SortingCertificate.closure(List.of(Cycle.of(0, 1, 2), Cycle.of(1, 0, 2)));
        assertThrows(IllegalArgumentException.class, () -> referenceRequiredBudget(fullRoot, unchanged));
        assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(fullRoot, unchanged));
    }

    private static void comparePrefixes(final CyclicTargetPair pair,
            final Cycle beta,
            final List<Cycle> prefix,
            final int remaining,
            final int[] checked) {
        assertMatchesReference(pair, SortingCertificate.closure(prefix));

        final boolean complete = assertMatchesReference(pair, SortingCertificate.complete(prefix));
        checked[0] += 2;
        checked[complete ? 1 : 2]++;
        if (remaining == 0) {
            return;
        }
        for (int i = 0; i < beta.size() - 2; i++) {
            for (int j = i + 1; j < beta.size() - 1; j++) {
                for (int k = j + 1; k < beta.size(); k++) {
                    val move = Cycle.of(beta.get(i), beta.get(j), beta.get(k));
                    val next = new ArrayList<>(prefix);
                    next.add(move);
                    comparePrefixes(pair,
                            CommonOperations.applyTranspositionOptimized(beta, move),
                            next,
                            remaining - 1,
                            checked);
                    // Reversing the last support is invalid in its actual parent order.
                    next.set(next.size() - 1, Cycle.of(move.get(0), move.get(2), move.get(1)));
                    assertMatchesReference(pair, SortingCertificate.closure(next));
                }
            }
        }
    }

    private static boolean assertMatchesReference(final CyclicTargetPair pair, final SortingCertificate result) {
        final int expected;

        try {
            expected = referenceRequiredBudget(pair, result);
        } catch (final IllegalArgumentException rejected) {
            assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(pair, result));
            return false;
        }

        assertEquals(expected, CertificateValidator.requiredBudget(pair, result));

        return true;
    }

    /**
     * The original object-based replay remains a separate oracle for the primitive implementation.
     */
    private static int referenceRequiredBudget(final CyclicTargetPair pair, final SortingCertificate result) {
        Cycle beta = pair.getBeta();
        MulticyclePermutation omega = pair.getOmega();
        for (val move : result.moves()) {
            if (move.size() != 3 || move.get(0) == move.get(1) || move.get(1) == move.get(2) || move.get(0) == move.get(2)) {
                throw new IllegalArgumentException("A move must contain three distinct symbols");
            }

            final int first = beta.indexOf(move.get(0));
            final int second = beta.indexOf(move.get(1));
            final int third = beta.indexOf(move.get(2));
            if (!(first < second && second < third)) {
                throw new IllegalArgumentException("Move is not an ordered triple");
            }

            omega = PermutationGroups.computeProduct(omega, move.getInverse());
            beta = CommonOperations.applyTranspositionOptimized(beta, move);
            if (!PermutationGroups.computeProduct(true, beta.size(), omega, beta).equals(new MulticyclePermutation(pair.getRho()))) {
                throw new IllegalArgumentException("Certificate move does not preserve the cyclic target");
            }
        }
        if (result.kind() == SortingCertificate.Kind.COMPLETE) {
            if (!omega.isIdentity()) {
                throw new IllegalArgumentException("Certificate does not sort");
            }

            return result.moves().size();
        }

        int nonfixed = 0;
        for (int symbol = 0; symbol < beta.size(); symbol++) {
            if (omega.image(symbol) != symbol) {
                nonfixed++;
            }
        }

        final int diameter = KnownDiameters.knownDiameter(nonfixed);
        if (diameter == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Closure uses an unknown diameter");
        }

        return Math.addExact(result.moves().size(), diameter);
    }

    private static int[] images(final MulticyclePermutation omega, final int n) {
        return IntStream.range(0, n).map(omega::image).toArray();
    }

    private static void permute(final int[] values, final int index, final Consumer<int[]> sink) {
        if (index == values.length) {
            sink.accept(values);
            return;
        }
        for (int i = index; i < values.length; i++) {
            final int temporary = values[index];
            values[index] = values[i];
            values[i] = temporary;
            permute(values, index + 1, sink);
            values[i] = values[index];
            values[index] = temporary;
        }
    }
}
