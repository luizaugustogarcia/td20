package br.unb.cic.td20.experimental;

import br.unb.cic.td20.certificate.CertificateCodec;
import br.unb.cic.td20.joint.CyclicTargetMoves;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveCanonicalizerTest {
    @Test void matchesExistingKeysForEveryValidPairThroughSevenSymbols() {
        var workspace = new PrimitiveCanonicalizer();
        int[] checked = {0};
        for (int n = 1; n <= 7; n++) {
            int[] beta = IntStream.range(0, n).toArray();
            permutations(beta.clone(), 0, omega -> {
                if (!CyclicTargetMoves.isFullTargetCycle(omega, beta)) return;
                var pair = pair(omega, beta);
                byte[] expected = CertificateCodec.keyFor(pair.canonicalRepresentative());
                assertArrayEquals(expected, workspace.keyFor(omega, beta, beta.length),
                        () -> "omega=" + Arrays.toString(omega));
                checked[0]++;
            });
        }
        assertEquals(874, checked[0]);
    }

    @Test void preservesArbitraryRelabelingFixedPointsAndAllProductionDomains() {
        var workspace = new PrimitiveCanonicalizer();
        var random = new Random(0x20ca110L);
        byte[] retained = workspace.keyFor(new int[]{0}, new int[]{0}, 1);
        for (int n = 21; n >= 1; n--) {
            for (int sample = 0; sample < 12; sample++) {
                int[] beta = shuffled(n, random), rho = shuffled(n, random);
                int[] nextRho = new int[n], omega = new int[n];
                for (int i = 0; i < n; i++) nextRho[rho[i]] = rho[(i + 1) % n];
                for (int i = 0; i < n; i++) omega[beta[(i + 1) % n]] = nextRho[beta[i]];
                int[] originalOmega = omega.clone(), originalBeta = beta.clone();
                var pair = pair(omega, beta);
                byte[] key = workspace.keyFor(omega, beta, n);
                assertArrayEquals(CertificateCodec.keyFor(pair.canonicalRepresentative()), key,
                        "Domain " + n + " sample " + sample);
                assertArrayEquals(originalOmega, omega);
                assertArrayEquals(originalBeta, beta);
                // The independent persisted-key decoder must accept the exact
                // payload, including zero padding, and recover the same class.
                assertEquals(pair.extendedToricClassKey(),
                        CertificateCodec.pairForKey(key, n).extendedToricClassKey());
            }
        }
        assertArrayEquals(new byte[]{0}, retained, "Returned keys must outlive workspace reuse");
    }

    @Test void rejectsUncheckedCandidatesAndMalformedDomains() {
        var workspace = new PrimitiveCanonicalizer();
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[]{2, 0, 1}, new int[]{0, 1, 2}, 3));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[]{0, 0}, new int[]{0, 1}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[]{0, 1}, new int[]{0, 0}, 2));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[]{-1}, new int[]{0}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[]{1}, new int[]{0}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[22], new int[22], 22));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[0], new int[0], 0));
        assertThrows(IllegalArgumentException.class,
                () -> workspace.keyFor(new int[1], new int[1], 2));
    }

    private static int[] shuffled(int n, Random random) {
        int[] result = IntStream.range(0, n).toArray();
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), value = result[i];
            result[i] = result[j]; result[j] = value;
        }
        return result;
    }

    private static CyclicTargetPair pair(int[] omega, int[] beta) {
        var permutation = new MulticyclePermutation();
        int visited = 0;
        for (int start = 0; start < omega.length; start++) {
            if ((visited & (1 << start)) != 0) continue;
            int x = start, size = 0;
            int[] cycle = new int[omega.length];
            do {
                visited |= 1 << x; cycle[size++] = x; x = omega[x];
            } while (x != start);
            // Exercise implicit fixed points as well as singleton colors.
            if (size > 1) permutation.add(Cycle.of(Arrays.copyOf(cycle, size)));
        }
        return CyclicTargetPair.ofOwned(permutation, Cycle.of(beta.clone()));
    }

    private static void permutations(int[] values, int at, Consumer<int[]> output) {
        if (at == values.length) { output.accept(values); return; }
        for (int i = at; i < values.length; i++) {
            int temporary = values[at]; values[at] = values[i]; values[i] = temporary;
            permutations(values, at + 1, output);
            temporary = values[at]; values[at] = values[i]; values[i] = temporary;
        }
    }
}
