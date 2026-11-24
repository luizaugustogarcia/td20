package br.unb.cic.td20.certificate;

import br.unb.cic.td20.permutation.Cycle;

import java.util.List;
import java.util.Objects;

/**
 * A complete witness or a replayable prefix closed by an established diameter.
 */
public record SortingCertificate(Kind kind, List<Cycle> moves) {

    public enum Kind {

        COMPLETE, DIAMETER_CLOSURE
    }

    public SortingCertificate {
        Objects.requireNonNull(kind);
        moves = List.copyOf(moves);
    }

    public static SortingCertificate complete(final List<Cycle> moves) {
        return new SortingCertificate(Kind.COMPLETE, moves);
    }

    public static SortingCertificate closure(final List<Cycle> moves) {
        return new SortingCertificate(Kind.DIAMETER_CLOSURE, moves);
    }
}
