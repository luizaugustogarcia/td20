package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CertificateCodecTest {

    @Test
    void classKeyRoundTripsItsCanonicalRepresentative() {
        val pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        val canonical = pair.canonicalRepresentative();

        val restored = CertificateCodec.pairForKey(CertificateCodec.keyFor(canonical), 3);

        assertEquals(canonical.extendedToricClassKey(),
                restored.extendedToricClassKey());
    }

    @Test
    void witnessTriplesRoundTrip() {
        val moves = List.of(Cycle.of(0, 4, 7),
                Cycle.of(1, 3, 8),
                Cycle.of(2, 5, 6));

        assertEquals(moves,
                CertificateCodec.decodeValue(CertificateCodec.valueFor(moves)));
    }

    @Test
    void rejectsImplicitLossOfNonstandardBeta() {
        val pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 2, 1)), Cycle.of(0, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> CertificateCodec.keyFor(pair));
    }

    @Test
    void matchesTheRawTd16EncodingAndRejectsNonzeroPadding() {
        val pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        val key = CertificateCodec.keyFor(pair);
        val value = CertificateCodec.valueFor(List.of(Cycle.of(0, 1, 2)));
        assertArrayEquals(new byte[] { 0x08, (byte) 0x80 }, key);
        assertArrayEquals(new byte[] { 0x00, 0x44 }, value);
        key[key.length - 1] |= 1;
        value[value.length - 1] |= 1;

        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.pairForKey(key, 3));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.decodeValue(value));
    }
}
