package br.unb.cic.td20.compat;

import br.unb.cic.td20.certificate.PrimitiveCertificate;
import br.unb.cic.td20.certificate.SortingCertificate;
import br.unb.cic.td20.permutation.Cycle;
import java.util.ArrayList;
import java.util.List;

/** Explicit compatibility adapters; no production sorting or replay loop uses cycle objects. */
public final class LegacyCertificates {
    private LegacyCertificates() { }
    public static PrimitiveCertificate fromLegacy(SortingCertificate result) {
        return new PrimitiveCertificate(PrimitiveCertificate.Kind.valueOf(result.kind().name()), pack(result.moves()));
    }
    public static int[] pack(List<Cycle> moves) {
        int[] packed = new int[moves.size()];
        for (int i = 0; i < packed.length; i++) {
            Cycle move = moves.get(i);
            if (move.size() != 3) throw new IllegalArgumentException("A certificate move must be a symbol triple");
            int a = move.get(0), b = move.get(1), c = move.get(2);
            if (a < 0 || b < 0 || c < 0 || a > 31 || b > 31 || c > 31)
                throw new IllegalArgumentException("Certificate symbols must lie in 0..31");
            packed[i] = a | (b << 5) | (c << 10);
        }
        return packed;
    }
    public static SortingCertificate toLegacy(PrimitiveCertificate result) {
        var moves = new ArrayList<Cycle>(result.moveCount());
        for (int i = 0; i < result.moveCount(); i++) {
            int move = result.moveAt(i);
            moves.add(Cycle.of(move & 31, (move >>> 5) & 31, (move >>> 10) & 31));
        }
        return new SortingCertificate(SortingCertificate.Kind.valueOf(result.kind().name()), List.copyOf(moves));
    }
}
