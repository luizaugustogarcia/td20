package br.unb.cic.td20.experimental;

import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InverseFamilyClosureTest {

    @Test
    void everyPredictedClosureReplaysOnEverySmallDomainInverseParentAndGap() {
        final long[] examined = { 0 }, predicted = { 0 }, wholeFamilies = { 0 };
        for (int m = 3; m <= 6; m++) {
            final int[] beta = IntStream.range(0, m).toArray();
            permutations(beta.clone(),
                    0,
                    omega -> {
                        for (int x = 0; x < omega.length; x++) {
                            if (omega[x] == x) {
                                return;
                            }
                        }

                        final PrimitivePair child;

                        try {
                            child = PrimitivePair.of(omega, beta);
                        } catch (final IllegalArgumentException notPair) {
                            return;
                        }
                        for (final boolean target : new boolean[] { false, true }) {
                            val filter = new InverseFamilyClosure();
                            filter.prepare(child, target);

                            int[] sourceOmega = child.omegaImages();
                            if (target) {
                                sourceOmega = inverse(sourceOmega);
                            }

                            final int[] sourceBeta = target ? child.rhoSymbols() : child.betaSymbols();
                            for (int gain = 0; gain <= 1; gain++) {
                                final boolean allClosed = filter.closesEverySupport(gain);
                                if (allClosed) {
                                    wholeFamilies[0]++;
                                }

                                final long[] residualSupports = { 0 };
                                for (int a = 0; a < beta.length - 1; a++) {
                                    for (int b = a + 1; b < beta.length; b++) {
                                        if (gain == 1) {
                                            final int[] support = { beta.length, a, b };
                                            final boolean closes = filter.guaranteesImmediateClosure((1 << a) | (1 << b));
                                            if (!closes) {
                                                residualSupports[0]++;
                                            }

                                            assertFalse(allClosed && !closes);
                                            for (int gap = 1; gap <= beta.length; gap++) {
                                                checkParent(sourceOmega, sourceBeta, support, gap, closes, examined, predicted);
                                            }
                                        } else {
                                            for (int c = b + 1; c < beta.length; c++) {
                                                final int[] support = { a, b, c };
                                                final boolean closes = filter.guaranteesImmediateClosure((1 << a) | (1 << b) | (1 << c));
                                                if (!closes) {
                                                    residualSupports[0]++;
                                                }

                                                assertFalse(allClosed && !closes);
                                                checkParent(sourceOmega, sourceBeta, support, -1, closes, examined, predicted);
                                            }
                                        }
                                    }
                                }

                                assertEquals(residualSupports[0], filter.unresolvedOldSupports(gain));
                                assertEquals(residualSupports[0] == 0, allClosed);
                            }
                        }
                    });
        }

        assertTrue(examined[0] > 6_000, Arrays.toString(examined));
        assertTrue(predicted[0] > 100, Arrays.toString(predicted));
        assertTrue(wholeFamilies[0] > 0);
    }

    private static void checkParent(final int[] sourceOmega,
            final int[] sourceBeta,
            final int[] support,
            final int gap,
            final boolean closes,
            final long[] examined,
            final long[] predicted) {
        final int m = sourceOmega.length, n = m + (gap < 0 ? 0 : 1);
        final int[] restoredOmega = Arrays.copyOf(sourceOmega, n), restoredBeta = new int[n];
        if (n > m) {
            restoredOmega[m] = m;
        }
        for (int i = 0, j = 0; i < n; i++) {
            restoredBeta[i] = i == gap ? m : sourceBeta[j++];
        }

        final int[] pos = positions(restoredBeta);
        final Integer[] ordered = { support[0], support[1], support[2] };
        Arrays.sort(ordered, (x, y) -> Integer.compare(pos[x], pos[y]));

        final int a = ordered[0], b = ordered[1], c = ordered[2];
        final int[] parentOmega = restoredOmega.clone();
        parentOmega[a] = restoredOmega[c];
        parentOmega[b] = restoredOmega[a];
        parentOmega[c] = restoredOmega[b];
        for (int x = 0; x < n; x++) {
            if (parentOmega[x] == x) {
                return;
            }
        }

        final int[] next = successors(restoredBeta), parentNext = new int[n];
        for (int x = 0; x < n; x++) {
            parentNext[x] = cycle(next[x], a, b, c);
        }

        final int[] parentBeta = order(parentNext);
        final PrimitivePair parent = PrimitivePair.of(parentOmega, parentBeta);
        // Replay the actual inverse orientation, including every current-cycle edge.
        final int[] replayOmega = parentOmega.clone();
        replayOmega[a] = parentOmega[b];
        replayOmega[b] = parentOmega[c];
        replayOmega[c] = parentOmega[a];
        assertArrayEquals(restoredOmega, replayOmega);

        int fixed = 0;
        for (int x = 0; x < n; x++) {
            assertEquals(next[x], cycle(parentNext[x], a, c, b));
            if (replayOmega[x] == x) {
                fixed++;
            }
        }

        assertEquals(n - m, fixed);
        examined[0]++;
        if (!closes) {
            return;
        }

        predicted[0]++;

        final int[] oppositeOmega = inverse(parentOmega), oppositeBeta = parent.rhoSymbols();
        // An independent arbitrary-support oracle, with full pair reconstruction,
        // validates the promised move on the endpoint that was not changed.
        boolean found = false;
        for (int i = 0; i < n - 2 && !found; i++) {
            for (int j = i + 1; j < n - 1 && !found; j++) {
                for (int k = j + 1; k < n && !found; k++) {
                    int x = oppositeBeta[i], y = oppositeBeta[j], z = oppositeBeta[k];
                    final int[] moved = oppositeOmega.clone();
                    moved[x] = oppositeOmega[z];
                    moved[y] = oppositeOmega[x];
                    moved[z] = oppositeOmega[y];

                    int gain = 0;
                    for (int t = 0; t < n; t++) {
                        if (moved[t] == t) {
                            gain++;
                        }
                    }
                    if (gain < 2) {
                        continue;
                    }

                    final int[] movedNext = successors(oppositeBeta);
                    for (int t = 0; t < n; t++) {
                        movedNext[t] = cycle(movedNext[t], x, y, z);
                    }

                    final PrimitivePair replayed = PrimitivePair.of(moved, order(movedNext));
                    assertArrayEquals(successors(parent.betaSymbols()), successors(replayed.rhoSymbols()));
                    found = true;
                }
            }
        }

        assertTrue(found, "Predicted family closure must have a replayable gain-two move");
    }

    @Test
    void rejectsUnpreparedUncontractedAndOutOfDomainUses() {
        val filter = new InverseFamilyClosure();
        assertThrows(IllegalStateException.class, () -> filter.guaranteesImmediateClosure(0));
        assertThrows(IllegalArgumentException.class,
                () -> filter.prepare(PrimitivePair.of(new int[] { 0, 1, 2 }, new int[] { 0, 1, 2 }), false));
        filter.prepare(PrimitivePair.of(new int[] { 1, 2, 0 }, new int[] { 0, 1, 2 }), false);
        assertThrows(IllegalArgumentException.class, () -> filter.guaranteesImmediateClosure(1 << 3));
        assertThrows(IllegalArgumentException.class, () -> filter.unresolvedOldSupports(2));
    }

    private static int cycle(final int x, final int a, final int b, final int c) {
        return x == a ? b : x == b ? c : x == c ? a : x;
    }

    private static int[] inverse(final int[] p) {
        val result = new int[p.length];
        for (int x = 0; x < p.length; x++) {
            result[p[x]] = x;
        }

        return result;
    }

    private static int[] positions(final int[] beta) {
        return inverse(beta);
    }

    private static int[] successors(final int[] beta) {
        val result = new int[beta.length];
        for (int i = 0; i < beta.length; i++) {
            result[beta[i]] = beta[(i + 1) % beta.length];
        }

        return result;
    }

    private static int[] order(final int[] next) {
        val result = new int[next.length];
        int x = 0, seen = 0;
        for (int i = 0; i < next.length; i++) {
            assertEquals(0, seen & (1 << x));
            seen |= 1 << x;
            result[i] = x;
            x = next[x];
        }

        assertEquals(0, x);

        return result;
    }

    private static void permutations(final int[] p, final int i, final Consumer<int[]> output) {
        if (i == p.length) {
            output.accept(p);
            return;
        }
        for (int j = i; j < p.length; j++) {
            final int t = p[i];
            p[i] = p[j];
            p[j] = t;
            permutations(p, i + 1, output);
            p[j] = p[i];
            p[i] = t;
        }
    }
}
