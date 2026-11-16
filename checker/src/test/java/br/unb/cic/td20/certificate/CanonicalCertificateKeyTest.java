package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetCandidate;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.verification.DirectStateKey;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalCertificateKeyTest {
    @Test void directKeysMatchTheIndependentDecodeAndPackPathThroughSevenSymbols() {
        for (int n = 1; n <= 7; n++) {
            int[] current = IntStream.range(0, n).toArray();
            permutations(current.clone(), 0, images -> {
                var candidate = new CyclicTargetCandidate(permutation(images, false), Cycle.of(current));
                candidate.asCyclicTargetPair().ifPresent(CanonicalCertificateKeyTest::compare);
            });
        }
    }

    @Test void arbitraryCurrentCyclesAndFullWireDomainsMatchIncludingFixedPoints() {
        var random = new Random(0x77425090L);
        for (int n : new int[]{14, 16, 18, 19, 20, 21, 23, 32}) {
            for (int sample = 0; sample < 50; sample++) {
                int[] current = shuffled(n, random), target = shuffled(n, random);
                int[] previous = new int[n], next = new int[n], images = new int[n];
                for (int i = 0; i < n; i++) {
                    previous[current[(i + 1) % n]] = current[i];
                    next[target[i]] = target[(i + 1) % n];
                }
                for (int x = 0; x < n; x++) images[x] = next[previous[x]];
                compare(CyclicTargetPair.ofOwned(permutation(images, sample % 2 == 0), Cycle.of(current)));
            }
        }
    }

    @Test void reconstructionPackingRejectsFixedPointsAndPreservesPrefixesAndPadding() {
        var implicit = CyclicTargetPair.ofOwned(new MulticyclePermutation(), Cycle.of(0, 1, 2));
        var explicit = CyclicTargetPair.ofOwned(new MulticyclePermutation(List.of(Cycle.of(0), Cycle.of(1), Cycle.of(2))),
                Cycle.of(0, 1, 2));
        for (var fixed : new CyclicTargetPair[]{implicit, explicit, implicit.canonicalRepresentative()}) {
            assertThrows(IllegalArgumentException.class, () -> CertificateCodec.writeFixedPointFreeCanonicalKey(
                    fixed, new byte[5], 3));
            compare(fixed);
        }
        var pair = CyclicTargetPair.ofOwned(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        byte[] expected = CertificateCodec.canonicalKeyFor(pair);
        byte[] output = new byte[expected.length + 4];
        Arrays.fill(output, (byte) 0x7f);
        CertificateCodec.writeFixedPointFreeCanonicalKey(pair, output, 3);
        assertArrayEquals(expected, Arrays.copyOfRange(output, 3, 3 + expected.length));
        assertArrayEquals(new byte[]{0x7f, 0x7f, 0x7f}, Arrays.copyOf(output, 3));
        assertEquals(0x7f, output[output.length - 1]);
        CertificateCodec.writeFixedPointFreeCanonicalKey(pair.canonicalRepresentative(), output, 3);
        assertArrayEquals(expected, Arrays.copyOfRange(output, 3, 3 + expected.length));
        assertThrows(IndexOutOfBoundsException.class,
                () -> CertificateCodec.writeFixedPointFreeCanonicalKey(pair, new byte[1], 0));
    }

    @Test void normalizedPairComparisonChecksActualImagesAndCurrentCycle() {
        var pair = CyclicTargetPair.ofOwned(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        byte[] key = CertificateCodec.canonicalKeyFor(pair);
        var decoded = CertificateCodec.pairForKey(key, 3);
        assertFalse(decoded.isKnownCanonicalRepresentative());
        assertTrue(CertificateCodec.matchesNormalizedPair(key, decoded));
        var rotated = CyclicTargetPair.ofOwned(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(1, 2, 0));
        assertFalse(CertificateCodec.matchesNormalizedPair(key, rotated));
        assertArrayEquals(key, CertificateCodec.canonicalKeyFor(rotated));
        var identity = CyclicTargetPair.ofOwned(new MulticyclePermutation(), Cycle.of(0, 1, 2));
        assertFalse(CertificateCodec.matchesNormalizedPair(key, identity));
        key[key.length - 1] |= 1;
        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.matchesNormalizedPair(key, decoded));
    }

    @Test void concurrentKeysOwnTheirBytesAndThreadLocalScratchCannotLeak() {
        var pair = CyclicTargetPair.ofOwned(new MulticyclePermutation(Cycle.of(0, 1, 2)), Cycle.of(0, 1, 2));
        byte[] expected = CertificateCodec.canonicalKeyFor(pair), retained = expected.clone();
        IntStream.range(0, 512).parallel().forEach(i -> {
            var current = (i & 1) == 0 ? pair : CyclicTargetPair.ofOwned(new MulticyclePermutation(), Cycle.of(0, 1, 2));
            byte[] result = CertificateCodec.canonicalKeyFor(current);
            assertArrayEquals(CertificateCodec.keyFor(current.canonicalRepresentative()), result);
            result[0] ^= 1;
        });
        assertArrayEquals(retained, expected);
        assertArrayEquals(expected, CertificateCodec.canonicalKeyFor(pair));
    }

    private static void compare(CyclicTargetPair pair) {
        byte[] direct = CertificateCodec.canonicalKeyFor(pair);
        var canonical = pair.canonicalRepresentative();
        assertArrayEquals(CertificateCodec.keyFor(canonical), direct);
        assertArrayEquals(direct, CertificateCodec.canonicalKeyFor(canonical));
        assertEquals(pair.extendedToricClassKey(),
                CertificateCodec.pairForKey(direct, pair.getBeta().size()).extendedToricClassKey());
        boolean fixedPointFree = true;
        for (int symbol : pair.getBeta().getSymbols())
            if (pair.getOmega().image(symbol) == symbol) fixedPointFree = false;
        if (fixedPointFree) {
            byte[] state = DirectStateKey.encode(pair, 9);
            assertEquals(3, state[0]);
            assertEquals(9, state[1]);
            assertEquals(pair.getBeta().size(), state[2]);
            assertArrayEquals(direct, Arrays.copyOfRange(state, 3, state.length));
        }
    }

    private static MulticyclePermutation permutation(int[] images, boolean explicitFixed) {
        var result = new MulticyclePermutation();
        var seen = new boolean[images.length];
        for (int x = 0; x < images.length; x++) if (!seen[x]) {
            int[] symbols = new int[images.length]; int at = 0, y = x;
            do { symbols[at++] = y; seen[y] = true; y = images[y]; } while (y != x);
            if (at > 1 || explicitFixed) result.add(Cycle.of(Arrays.copyOf(symbols, at)));
        }
        return result;
    }

    private static int[] shuffled(int n, Random random) {
        int[] result = IntStream.range(0, n).toArray();
        for (int i = n - 1; i > 0; i--) swap(result, i, random.nextInt(i + 1));
        return result;
    }

    private static void permutations(int[] values, int at, Consumer<int[]> output) {
        if (at == values.length) { output.accept(values); return; }
        for (int i = at; i < values.length; i++) {
            swap(values, at, i); permutations(values, at + 1, output); swap(values, at, i);
        }
    }

    private static void swap(int[] values, int i, int j) {
        int value = values[i]; values[i] = values[j]; values[j] = value;
    }
}
