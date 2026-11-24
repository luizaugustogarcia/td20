package br.unb.cic.td20.experimental;

import br.unb.cic.td20.avoidance.ErikssonInverseReconstruction;
import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PrimitiveInverseReconstructionTest {

    @Test
    void completeSmallDomainOutputsMatchLegacyIncludingMultiplicityAndEndpointOrder() {
        val primitive = new PrimitiveInverseReconstruction();
        final int[] checked = { 0 };
        for (int n = 3; n <= 6; n++) {
            final int[] beta = IntStream.range(0, n).toArray();
            permutations(beta.clone(),
                    0,
                    omega -> {
                        for (int x = 0; x < omega.length; x++) {
                            if (omega[x] == x) {
                                return;
                            }
                        }
                        if (!CyclicTargetMoves.isFullTargetCycle(omega, beta)) {
                            return;
                        }

                        val child = pair(omega, beta);
                        for (int gain = 0; gain <= 3; gain++) {
                            final List<ByteBuffer> expected = new ArrayList<>();
                            ErikssonInverseReconstruction.preimages(child, gain, parent -> expected.add(raw(parent)));

                            final List<ByteBuffer> actual = new ArrayList<>();
                            final long count = primitive.preimages(child,
                                    gain,
                                    (p, current, length) -> actual.add(raw(p, current, length)));
                            assertEquals(expected, actual, "N=" + omega.length + " gain=" + gain + " child=" + child);
                            assertEquals(expected.size(), count);
                            checked[0]++;
                        }
                    });
        }

        assertTrue(checked[0] > 100);
    }

    @Test
    void fewActiveDomainFixturesHaveExactlyTheLegacyCanonicalClassSets() {
        val primitive = new PrimitiveInverseReconstruction();
        val canonicalizer = new PrimitiveCanonicalizer();
        final int[][] fixtures = { { 18, 4, 2 }, { 18, 4, 3 }, { 20, 2, 1 }, { 21, 1, 0 } };
        for (final int[] fixture : fixtures) {
            val child = shiftedPair(fixture[0], fixture[1]);
            final Set<ByteBuffer> expected = new HashSet<>();
            final long[] expectedCount = { 0 };
            ErikssonInverseReconstruction.preimages(child,
                    fixture[2],
                    parent -> {
                        expected.add(ByteBuffer.wrap(CertificateCodec.keyFor(parent.canonicalRepresentative())));
                        expectedCount[0]++;
                    });

            final Set<ByteBuffer> actual = new HashSet<>();
            final long actualCount = primitive.preimages(child,
                    fixture[2],
                    (omega, beta, length) -> {
                        actual.add(ByteBuffer.wrap(canonicalizer.keyFor(omega, beta, length)));
                        for (int x = 0; x < length; x++) {
                            assertNotEquals(x, omega[x]);
                        }

                        assertTrue(CyclicTargetMoves.isFullTargetCycle(Arrays.copyOf(omega, length), Arrays.copyOf(beta, length)));
                    });
            assertFalse(expected.isEmpty());
            assertEquals(expectedCount[0], actualCount, "Raw count: " + Arrays.toString(fixture));
            assertEquals(expected, actual, "Canonical classes: " + Arrays.toString(fixture));
        }
    }

    @Test
    void arbitraryCurrentAnchorAndWorkspaceReusePreserveExactOutputs() {
        val primitive = new PrimitiveInverseReconstruction();
        final int[] beta = { 3, 4, 5, 6, 0, 1, 2 };
        final int[] omega = { 1, 2, 3, 4, 5, 6, 0 };
        val child = pair(omega, beta);
        for (final int gain : new int[] { 3, 0, 2, 1, 0 }) {
            final List<ByteBuffer> expected = new ArrayList<>();
            ErikssonInverseReconstruction.preimages(child, gain, parent -> expected.add(raw(parent)));

            final List<ByteBuffer> actual = new ArrayList<>();
            primitive.preimages(child, gain, (p, current, length) -> actual.add(raw(p, current, length)));
            assertEquals(expected, actual);
        }
    }

    @Test
    void rejectsUncontractedInputsInvalidGainsAndReentrantCallbacksAndRecoversAfterFailure() {
        val primitive = new PrimitiveInverseReconstruction();
        val child = shiftedPair(7, 1);
        val noop = (PrimitiveInverseReconstruction.RawConsumer) (omega, beta, length) -> {
        };
        assertThrows(IllegalArgumentException.class, () -> primitive.preimages(child, -1, noop));
        assertThrows(IllegalArgumentException.class, () -> primitive.preimages(child, 4, noop));
        assertThrows(IllegalArgumentException.class, () -> primitive.preimages(shiftedPair(21, 1), 1, noop));
        assertThrows(IllegalArgumentException.class,
                () -> primitive.preimages(pair(new int[] { 0, 1, 2 }, new int[] { 0, 1, 2 }), 0, noop));
        assertThrows(IllegalStateException.class,
                () -> primitive.preimages(child, 1, (omega, beta, length) -> primitive.preimages(child, 0, noop)));

        val failure = new IllegalArgumentException("callback failure");
        assertSame(failure,
                assertThrows(IllegalArgumentException.class,
                        () -> primitive.preimages(child, 1, (omega, beta, length) -> {
                            throw failure;
                        })));

        final List<ByteBuffer> expected = new ArrayList<>();
        ErikssonInverseReconstruction.preimages(child, 1, parent -> expected.add(raw(parent)));

        final List<ByteBuffer> actual = new ArrayList<>();
        assertEquals(expected.size(),
                primitive.preimages(child,
                        1,
                        (omega, beta, length) -> actual.add(raw(omega, beta, length))));
        assertEquals(expected, actual);
    }

    private static CyclicTargetPair shiftedPair(final int n, final int shift) {
        val omega = new int[n];
        for (int x = 0; x < n; x++) {
            omega[x] = (x + shift) % n;
        }

        return pair(omega, IntStream.range(0, n).toArray());
    }

    private static ByteBuffer raw(final CyclicTargetPair pair) {
        final int n = pair.getBeta().size();
        val omega = new int[n];
        for (int x = 0; x < n; x++) {
            omega[x] = pair.getOmega().image(x);
        }

        return raw(omega, pair.getBeta().getSymbols(), n);
    }

    private static ByteBuffer raw(final int[] omega, final int[] beta, final int n) {
        val bytes = new byte[2 * n];
        for (int x = 0; x < n; x++) {
            bytes[x] = (byte) omega[x];
            bytes[n + x] = (byte) beta[x];
        }

        return ByteBuffer.wrap(bytes);
    }

    private static CyclicTargetPair pair(final int[] omega, final int[] beta) {
        val permutation = new MulticyclePermutation();
        val cycle = new int[omega.length];
        int visited = 0;
        for (int start = 0; start < omega.length; start++) {
            if ((visited & (1 << start)) != 0) {
                continue;
            }

            int size = 0;
            int x = start;
            do {
                visited |= 1 << x;
                cycle[size++] = x;
                x = omega[x];
            } while (x != start);

            permutation.add(Cycle.of(Arrays.copyOf(cycle, size)));
        }

        return CyclicTargetPair.ofOwned(permutation, Cycle.of(beta.clone()));
    }

    private static void permutations(final int[] p, final int index, final Consumer<int[]> output) {
        if (index == p.length) {
            output.accept(p);
            return;
        }
        for (int j = index; j < p.length; j++) {
            final int t = p[index];
            p[index] = p[j];
            p[j] = t;
            permutations(p, index + 1, output);
            p[j] = p[index];
            p[index] = t;
        }
    }
}
