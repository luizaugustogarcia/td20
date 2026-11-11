package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SortingCertificateTest {
    private static CyclicTargetPair pair() {
        return CyclicTargetPair.of(new MulticyclePermutation("(0 2 4 6 1 3 5)"),Cycle.of(0,1,2,3,4,5,6));
    }
    @Test void closureReplaysFourMovesWithZeroFixedPointGain() {
        var moves = List.of(Cycle.of(0,1,2),Cycle.of(1,0,2),Cycle.of(0,1,2),Cycle.of(1,0,2));
        var record = SortingCertificate.closure(moves);
        assertEquals(8,CertificateValidator.requiredBudget(pair(),record));
        CertificateValidator.validate(pair(),record,8);
        assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair(),record,7));
        assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair(),SortingCertificate.complete(moves),8));
        assertEquals(record,CertificateCodec.decodeResult(CertificateCodec.resultFor(record)));
    }
    @Test void existingFixedPointsPermitAZeroMoveClosure() {
        var pair = CyclicTargetPair.of(new MulticyclePermutation("(0)(1 2 3)"),Cycle.of(0,1,2,3));
        var record = SortingCertificate.closure(List.of());
        assertEquals(1,CertificateValidator.requiredBudget(pair,record));
        assertEquals(record,CertificateCodec.decodeResult(new byte[]{1,0}));
        CertificateValidator.validate(pair,record,1);
        assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair,record,0));
    }
    @Test void unknownDiameterCannotCertifyTheTwentyOneSymbolDomain() {
        int[] symbols = java.util.stream.IntStream.range(0,21).toArray();
        var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(symbols)),Cycle.of(symbols));
        assertThrows(IllegalArgumentException.class,()->CertificateValidator.validate(pair,SortingCertificate.closure(List.of()),11));
    }
    @Test void codecRejectsUnknownKindsCountsTruncationAndPadding() {
        assertThrows(IllegalArgumentException.class,()->CertificateCodec.decodeResult(new byte[0]));
        assertThrows(IllegalArgumentException.class,()->CertificateCodec.decodeResult(new byte[]{2,0}));
        assertThrows(IllegalArgumentException.class,()->CertificateCodec.decodeResult(new byte[]{1,11}));
        assertThrows(IllegalArgumentException.class,()->CertificateCodec.decodeResult(new byte[]{1,1}));
        assertThrows(IllegalArgumentException.class,()->CertificateCodec.decodeResult(new byte[]{1,0,0}));
        var encoded = CertificateCodec.resultFor(SortingCertificate.closure(List.of(Cycle.of(0,1,2))));
        encoded[encoded.length-1] |= 1;
        assertThrows(IllegalArgumentException.class,()->CertificateCodec.decodeResult(encoded));
    }
}
