package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CertificateCodecTest {
    @Test
    void classKeyRoundTripsItsCanonicalRepresentative() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var canonical = pair.canonicalRepresentative();

        final var restored = CertificateCodec.pairForKey(
                CertificateCodec.keyFor(canonical), 3);

        assertEquals(canonical.extendedToricClassKey(),
                restored.extendedToricClassKey());
    }

    @Test
    void witnessTriplesRoundTrip() {
        final var moves = List.of(
                Cycle.of(0, 4, 7),
                Cycle.of(1, 3, 8),
                Cycle.of(2, 5, 6));

        assertEquals(moves,
                CertificateCodec.decodeValue(CertificateCodec.valueFor(moves)));
    }

    @Test
    void matchesTheRawTd16EncodingAndRejectsNonzeroPadding() {
        final var pair = CyclicTargetPair.of(
                new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
        final var key = CertificateCodec.keyFor(pair);
        final var value = CertificateCodec.valueFor(List.of(Cycle.of(0, 1, 2)));
        assertArrayEquals(new byte[]{0x08, (byte) 0x80}, key);
        assertArrayEquals(new byte[]{0x00, 0x44}, value);
        key[key.length - 1] |= 1;
        value[value.length - 1] |= 1;

        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.pairForKey(key, 3));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.decodeValue(value));
    }
}
