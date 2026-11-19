package br.unb.cic.td20.certificate;

import br.unb.cic.td20.compat.LegacyCertificates;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveCertificateTest {
    @Test void keysMatchLegacyBytesAcrossAllActiveDomains() {
        var random = new Random(429701);
        for (int n = 1; n <= 21; n++) {
            for (int sample = 0; sample < 3; sample++) {
                int[] target = IntStream.range(0, n).toArray();
                for (int i = n - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1), old = target[i];
                    target[i] = target[j]; target[j] = old;
                }
                int[] omega = new int[n];
                for (int i = 0; i < n; i++) omega[(target[i] + 1) % n] = target[(i + 1) % n];
                var pair = PrimitivePair.of(omega, IntStream.range(0, n).toArray());
                var legacy = LegacyPairs.toLegacy(pair);
                byte[] key = CertificateCodec.keyFor(pair);
                assertArrayEquals(CertificateCodec.keyFor(legacy), key);
                assertArrayEquals(omega, CertificateCodec.primitivePairForKey(key, n).omegaImages());
                assertEquals(pair, CertificateCodec.primitivePairForKey(key));
                assertEquals(legacy.extendedToricClassKey(), CertificateCodec.pairForKey(key, n).extendedToricClassKey());
            }
        }
    }

    @Test void everyMovePackingBoundaryMatchesLegacyAndIndependentBitOracle() {
        for (int count = 0; count <= 11; count++) {
            var moves = new ArrayList<Cycle>();
            for (int i = 0; i < count; i++) moves.add(Cycle.of((3 * i) % 32, (3 * i + 1) % 32, (3 * i + 2) % 32));
            for (var kind : SortingCertificate.Kind.values()) {
                var legacy = new SortingCertificate(kind, moves);
                var primitive = LegacyCertificates.fromLegacy(legacy);
                byte[] encoded = CertificateCodec.resultFor(primitive);
                assertArrayEquals(CertificateCodec.resultFor(legacy), encoded);
                byte[] expected = new byte[2 + (15 * count + 7) / 8];
                expected[0] = (byte) kind.ordinal(); expected[1] = (byte) count;
                for (int i = 0; i < count; i++) {
                    for (int j = 0; j < 3; j++) writeBits(expected, 16 + 15 * i + 5 * j, moves.get(i).get(j));
                }
                assertArrayEquals(expected, encoded);
                assertEquals(primitive, CertificateCodec.decodePrimitiveResult(encoded));
                assertEquals(legacy, LegacyCertificates.toLegacy(primitive));
                if (count > 0) {
                    assertArrayEquals(CertificateCodec.valueFor(moves), CertificateCodec.valueFor(primitive));
                    assertEquals(PrimitiveCertificate.complete(primitive.packedMoves()),
                            CertificateCodec.decodePrimitiveValue(CertificateCodec.valueFor(primitive)));
                }
            }
        }
    }

    @Test void replaysProductionLengthStatesAndRejectsMalformedOrUnorderedMoves() {
        for (int n : new int[]{16, 18, 20, 21, 3}) {
            int[] omega = IntStream.range(0, n).toArray();
            omega[0] = 1; omega[1] = 2; omega[2] = 0;
            var pair = PrimitivePair.of(omega, IntStream.range(0, n).toArray());
            var complete = PrimitiveCertificate.complete(new int[]{pack(0, 1, 2)});
            assertEquals(1, CertificateValidator.requiredBudget(pair, complete));
            assertEquals(1, CertificateValidator.requiredBudget(pair, PrimitiveCertificate.closure(new int[0])));
            assertArrayEquals(omega, pair.omegaImages());
            assertThrows(IllegalArgumentException.class, () -> CertificateValidator.validatedRequiredBudget(pair, complete, 0));
            assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(pair,
                    PrimitiveCertificate.complete(new int[0])));
            assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(pair,
                    PrimitiveCertificate.closure(new int[]{pack(0, 2, 1)})));
            assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(pair,
                    PrimitiveCertificate.closure(new int[]{pack(0, 1, 2), pack(0, 1, 2)})));
            assertThrows(IllegalArgumentException.class, () -> CertificateValidator.requiredBudget(pair,
                    PrimitiveCertificate.closure(new int[]{pack(0, 1, 31)})));
        }
        assertThrows(IllegalArgumentException.class, () -> PrimitiveCertificate.complete(new int[]{pack(0, 1, 1)}));
        assertThrows(IllegalArgumentException.class, () -> PrimitiveCertificate.complete(new int[]{1 << 15}));
        assertThrows(IllegalArgumentException.class, () -> PrimitiveCertificate.complete(new int[12]));
    }

    @Test void rejectsMalformedResultsAndKeysWithoutDroppingData() {
        for (byte[] encoded : List.of(new byte[0], new byte[]{2, 0}, new byte[]{1, 12}, new byte[]{0, 1},
                new byte[]{1, 0, 0}, new byte[]{0, 1, 0, 0x45}, new byte[]{0, 1, 0, 0}))
            assertThrows(IllegalArgumentException.class, () -> CertificateCodec.decodePrimitiveResult(encoded));
        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.decodePrimitiveValue(new byte[23]));
        var pair = PrimitivePair.of(new int[]{1, 2, 0}, new int[]{0, 1, 2});
        byte[] key = CertificateCodec.keyFor(pair); key[key.length - 1] |= 1;
        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.primitivePairForKey(key, 3));
        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.primitivePairForKey(new byte[14]));
        var rotated = PrimitivePair.of(new int[]{1, 2, 0}, new int[]{1, 2, 0});
        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.keyFor(rotated));
    }

    @Test void arbitraryMultiMoveWitnessesAndPrefixesMatchTheLegacyCheckerReplay() {
        IntStream.rangeClosed(3,21).parallel().forEach(n -> {
            var random=new Random(9327+n);
            for(int sample=0;sample<8;sample++) {
                int[] initial=IntStream.range(0,n).toArray();
                for(int i=n-1;i>0;i--) {int j=random.nextInt(i+1),t=initial[i];initial[i]=initial[j];initial[j]=t;}
                Cycle current=Cycle.of(initial.clone());
                var moves=new ArrayList<Cycle>();
                for(int step=0;step<11;step++) {
                    int[] positions=random.ints(0,n).distinct().limit(3).sorted().toArray();
                    Cycle move=Cycle.of(current.get(positions[0]),current.get(positions[1]),current.get(positions[2]));
                    moves.add(move);
                    current=br.unb.cic.td20.search.CommonOperations.applyTranspositionOptimized(current,move);
                }
                int[] images=new int[n];
                for(int i=0;i<n;i++) images[initial[(i+1)%n]]=current.image(initial[i]);
                PrimitivePair source=PrimitivePair.of(images,initial);
                var legacy=LegacyPairs.toLegacy(source);
                for(int count=0;count<=11;count++) {
                    var prefix=SortingCertificate.closure(moves.subList(0,count));
                    assertEquals(CertificateValidator.requiredBudget(legacy,prefix),
                            CertificateValidator.requiredBudget(source,LegacyCertificates.fromLegacy(prefix)));
                }
                var complete=SortingCertificate.complete(moves);
                assertEquals(11,CertificateValidator.requiredBudget(source,LegacyCertificates.fromLegacy(complete)));
                assertEquals(CertificateValidator.requiredBudget(legacy,complete),
                        CertificateValidator.requiredBudget(source,LegacyCertificates.fromLegacy(complete)));
                assertArrayEquals(images,source.omegaImages());
                assertArrayEquals(initial,source.betaSymbols());
            }
        });
    }

    @Test void packedCertificateOwnsItsMoves() {
        int[] source = {pack(0, 1, 2)};
        var certificate = PrimitiveCertificate.complete(source);
        source[0] = pack(0, 2, 1);
        int[] exposed = certificate.packedMoves(); exposed[0] = pack(0, 2, 1);
        assertEquals(pack(0, 1, 2), certificate.moveAt(0));
    }

    private static int pack(int a, int b, int c) { return a | b << 5 | c << 10; }
    private static void writeBits(byte[] bytes, int offset, int symbol) {
        for (int i = 0; i < 5; i++) if (((symbol >>> (4 - i)) & 1) != 0) {
            int bit = offset + i; bytes[bit >>> 3] |= (byte) (1 << (7 - (bit & 7)));
        }
    }
}
