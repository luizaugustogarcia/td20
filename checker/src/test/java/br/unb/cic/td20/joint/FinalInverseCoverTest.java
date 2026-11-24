package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.ExtendedToricClassKey;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FinalInverseCoverTest {

    @Test
    void completeResidualMultisetsAgreeWithArbitrarySupportOracleThroughSevenSymbols() {
        final long[] cases = { 0 }, closedFamilies = { 0 }, residuals = { 0 };
        for (int n = 3; n <= 7; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            permutations(beta.clone(),
                    0,
                    images -> {
                        for (int x = 0; x < images.length; x++) {
                            if (images[x] == x) {
                                return;
                            }
                        }

                        CyclicTargetPair child;

                        try {
                            child = pair(images, beta);
                        } catch (final IllegalStateException | IllegalArgumentException notPair) {
                            return;
                        }
                        for (int gain = 0; gain <= 1; gain++) {
                            val expected = unclosedMultiset(child, gain);
                            val actual = new HashMap<ExtendedToricClassKey, Long>();
                            val cover = new FinalInverseCover();
                            cover.forEachResidual(child,
                                    gain,
                                    parent -> {
                                        assertFalse(hasImmediateMove(parent));
                                        actual.merge(parent.extendedToricClassKey(), 1L, Long::sum);
                                    });
                            assertEquals(expected, actual, "source=" + Arrays.toString(images) + " gain=" + gain);

                            val count = cover.counts();
                            assertEquals(1, count.sources());
                            assertEquals(2, count.sourceSides());
                            assertEquals(2L * (gain == 0 ? choose3(images.length) : images.length * (images.length - 1) / 2) * (gain == 0 ? 1 : images.length),
                                    count.candidateSlots());
                            assertEquals(actual.values().stream().mapToLong(Long::longValue).sum(), count.residualParents());
                            cases[0]++;
                            closedFamilies[0] += count.closedSides();
                            residuals[0] += count.residualParents();
                        }
                    });
        }

        assertTrue(cases[0] > 100);
        assertTrue(closedFamilies[0] > 0);
        assertTrue(residuals[0] > 0);
    }

    @Test
    void fullLengthRelabeledAndReanchoredSourcesRetainExactResidualMultisets() {
        val random = new Random(20261119);
        for (final int n : new int[] { 20, 21 }) {
            for (int fixture = 0; fixture < 4; fixture++) {
                final int[] beta = IntStream.range(0, n).toArray(), rho = beta.clone(), images = new int[n];
                for (;;) {
                    shuffle(beta, random);
                    shuffle(rho, random);
                    for (int i = 0; i < n; i++) {
                        images[beta[(i + 1) % n]] = rhoNext(rho, beta[i]);
                    }

                    boolean fixed = false;
                    for (int x = 0; x < n; x++) {
                        fixed |= images[x] == x;
                    }
                    if (!fixed) {
                        break;
                    }
                }

                final CyclicTargetPair child = pair(images, beta);
                final int gain = 21 - n;
                val expected = unclosedMultiset(child, gain);
                val actual = new HashMap<ExtendedToricClassKey, Long>();
                val cover = new FinalInverseCover();
                cover.forEachResidual(child, gain, parent -> actual.merge(parent.extendedToricClassKey(), 1L, Long::sum));
                assertEquals(expected, actual, "full domain=" + n + " fixture=" + fixture);
                assertEquals(actual.values().stream().mapToLong(Long::longValue).sum(), cover.counts().residualParents());
            }
        }
    }

    @Test
    void invalidInputAndInterruptedCoverageCannotSupplyCounts() {
        val cover = new FinalInverseCover();
        assertThrows(IllegalArgumentException.class,
                () -> cover.forEachResidual(pair(new int[] { 0, 1, 2 }, new int[] { 0, 1, 2 }), 0, p -> {
                }));

        val child = pair(new int[] { 2, 3, 4, 5, 6, 0, 1 }, IntStream.range(0, 7).toArray());
        assertThrows(IllegalArgumentException.class, () -> cover.forEachResidual(child, 2, p -> {
        }));

        final boolean[] found = { false };
        final int[] beta = IntStream.range(0, 7).toArray();
        permutations(beta.clone(),
                0,
                images -> {
                    if (found[0]) {
                        return;
                    }
                    for (int x = 0; x < images.length; x++) {
                        if (images[x] == x) {
                            return;
                        }
                    }

                    CyclicTargetPair source;

                    try {
                        source = pair(images, beta);
                    } catch (final IllegalStateException | IllegalArgumentException notPair) {
                        return;
                    }
                    if (unclosedMultiset(source, 0).isEmpty()) {
                        return;
                    }

                    found[0] = true;

                    val failed = new FinalInverseCover();
                    assertThrows(IllegalStateException.class,
                            () -> failed.forEachResidual(source, 0, p -> {
                                throw new IllegalStateException("writer stopped");
                            }));
                    assertThrows(IllegalStateException.class, failed::counts);
                    assertThrows(IllegalStateException.class, () -> failed.forEachResidual(source, 0, p -> {
                    }));
                });
        assertTrue(found[0]);
        assertThrows(IllegalArgumentException.class, () -> new FinalInverseCover.Counts(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static Map<ExtendedToricClassKey, Long> unclosedMultiset(final CyclicTargetPair child, final int gain) {
        val result = new HashMap<ExtendedToricClassKey, Long>();
        ErikssonInverseReconstruction.preimages(child,
                gain,
                parent -> {
                    if (!hasImmediateMove(parent)) {
                        result.merge(parent.extendedToricClassKey(), 1L, Long::sum);
                    }
                });

        return result;
    }

    // Exhaust all arbitrary supports and calculate every changed image. This
    // oracle does not use consecutive triples, masks, or a cover calculation.
    static boolean hasImmediateMove(final CyclicTargetPair pair) {
        final int n = pair.getBeta().size();
        final int[] original = CyclicTargetMoves.images(pair), inverse = new int[n];
        for (int x = 0; x < n; x++) {
            inverse[original[x]] = x;
        }
        for (int endpoint = 0; endpoint < 2; endpoint++) {
            final int[] images = endpoint == 0 ? original : inverse;
            final int[] order = (endpoint == 0 ? pair.getBeta() : pair.getRho()).getSymbols();
            for (int i = 0; i < n - 2; i++) {
                for (int j = i + 1; j < n - 1; j++) {
                    for (int k = j + 1; k < n; k++) {
                        final int[] moved = CyclicTargetMoves.apply(images, order[i], order[j], order[k]);
                        if (CyclicTargetMoves.fixed(moved) >= 2) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static int rhoNext(final int[] rho, final int symbol) {
        for (int i = 0; i < rho.length; i++) {
            if (rho[i] == symbol) {
                return rho[(i + 1) % rho.length];
            }
        }
        throw new IllegalArgumentException();
    }

    private static long choose3(final int n) {
        return (long) n * (n - 1) * (n - 2) / 6;
    }

    private static void shuffle(final int[] p, final Random random) {
        for (int i = p.length - 1; i > 0; i--) {
            final int j = random.nextInt(i + 1), t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
    }

    private static CyclicTargetPair pair(final int[] images, final int[] beta) {
        val permutation = new MulticyclePermutation();
        int visited = 0;
        for (int x = 0; x < images.length; x++) {
            if ((visited & (1 << x)) != 0) {
                continue;
            }

            val cycle = new int[images.length];
            int length = 0, y = x;
            do {
                visited |= 1 << y;
                cycle[length++] = y;
                y = images[y];
            } while (y != x);

            permutation.add(Cycle.of(Arrays.copyOf(cycle, length)));
        }

        return CyclicTargetPair.ofOwned(permutation, Cycle.of(beta.clone()));
    }

    private static void permutations(final int[] p, final int at, final Consumer<int[]> output) {
        if (at == p.length) {
            output.accept(p);
            return;
        }
        for (int j = at; j < p.length; j++) {
            final int t = p[at];
            p[at] = p[j];
            p[j] = t;
            permutations(p, at + 1, output);
            p[j] = p[at];
            p[at] = t;
        }
    }
}
