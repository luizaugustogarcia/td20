package br.unb.cic.td20.model;

import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.certificate.CertificateCodec;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimitivePairTest {
    @Test void immutableStatesPreserveTargetsFixedPointsAndKeysAcrossAllDomains() {
        var random = new Random(0x20ba17L);
        for (int n = 1; n <= 21; n++) for (int sample = 0; sample < 5; sample++) {
            int[] beta = shuffled(n,random), rho = shuffled(n,random), omega = new int[n], target = new int[n];
            for (int i = 0; i < n; i++) target[rho[i]] = rho[(i + 1) % n];
            for (int i = 0; i < n; i++) omega[beta[(i + 1) % n]] = target[beta[i]];
            var pair = PrimitivePair.of(omega,beta);
            var legacy = LegacyPairs.toLegacy(pair);
            assertArrayEquals(CertificateCodec.keyFor(legacy.canonicalRepresentative()),pair.canonicalKey());
            assertArrayEquals(pair.canonicalKey(),pair.canonicalRepresentative().normalizedKey());
            assertSame(pair.canonicalRepresentative(),pair.canonicalRepresentative().canonicalRepresentative());
            assertEquals(legacy.getOmega().get3Norm(),pair.threeNorm());
            for (int x = 0; x < n; x++) assertEquals(omega[x] == x,(pair.fixedMask() & (1 << x)) != 0);
            for (int i = 0; i < n; i++) assertEquals(target[pair.rhoAt(i)],pair.rhoAt((i + 1) % n));
            assertEquals(pair,pair.swapped().swapped());
            byte[] key = pair.canonicalKey();
            Arrays.fill(key,(byte)31); Arrays.fill(omega,0); Arrays.fill(beta,0);
            Arrays.fill(pair.omegaBytes(),(byte)0); Arrays.fill(pair.betaBytes(),(byte)0);
            assertArrayEquals(CertificateCodec.keyFor(legacy.canonicalRepresentative()),pair.canonicalKey());
        }
    }

    @Test void equalityIgnoresCyclicAnchorWhileAccessorsPreserveIt() {
        int[] omega = {1,2,3,4,0};
        var first = PrimitivePair.of(omega,new int[]{0,1,2,3,4});
        var rotated = PrimitivePair.of(omega,new int[]{3,4,0,1,2});
        assertEquals(first,rotated); assertEquals(first.hashCode(),rotated.hashCode());
        assertEquals(3,rotated.betaAt(0));
        assertThrows(IllegalArgumentException.class,rotated::normalizedKey);
    }

    @Test void rejectsInvalidPairsAndMalformedPersistedKeys() {
        assertThrows(IllegalArgumentException.class,()->PrimitivePair.of(new int[]{2,0,1},new int[]{0,1,2}));
        assertThrows(IllegalArgumentException.class,()->PrimitivePair.of(new int[]{0,0},new int[]{0,1}));
        assertThrows(IllegalArgumentException.class,()->PrimitivePair.of(new int[]{0,1},new int[]{0,0}));
        assertThrows(IllegalArgumentException.class,()->PrimitivePair.of(new int[22],new int[22]));
        assertThrows(IllegalArgumentException.class,()->PrimitivePair.fromCanonicalKey(new byte[]{1},1));
        assertThrows(IllegalArgumentException.class,()->PrimitivePair.ofCanonicalNormalized(new int[]{1,2,0},new int[]{0,1,2}));
    }

    private static int[] shuffled(int n,Random random) {
        int[] result = IntStream.range(0,n).toArray();
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), temporary = result[i]; result[i] = result[j]; result[j] = temporary;
        }
        return result;
    }
}
