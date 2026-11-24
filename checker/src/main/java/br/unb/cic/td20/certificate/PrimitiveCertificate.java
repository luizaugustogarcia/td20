package br.unb.cic.td20.certificate;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable intrinsic certificate with packed transposition triples, without cycle objects.
 */
public final class PrimitiveCertificate {

    public enum Kind {

        COMPLETE, DIAMETER_CLOSURE
    }

    private final Kind kind;
    private final int[] moves;

    public PrimitiveCertificate(final Kind kind, final int[] packedMoves) {
        this(kind, packedMoves, false);
    }

    private PrimitiveCertificate(final Kind kind, final int[] packedMoves, final boolean owned) {
        this.kind = Objects.requireNonNull(kind);
        Objects.requireNonNull(packedMoves);
        if (packedMoves.length > 11) {
            throw new IllegalArgumentException("Certificate exceeds eleven moves");
        }

        this.moves = owned ? packedMoves : packedMoves.clone();
        for (final int move : moves) {
            final int a = move & 31, b = (move >>> 5) & 31, c = (move >>> 10) & 31;
            if (move < 0 || move >= (1 << 15) || a == b || b == c || a == c) {
                throw new IllegalArgumentException("A transposition requires three distinct five-bit symbols");
            }
        }
    }

    public static PrimitiveCertificate complete(final int[] moves) {
        return new PrimitiveCertificate(Kind.COMPLETE, moves);
    }

    public static PrimitiveCertificate closure(final int[] moves) {
        return new PrimitiveCertificate(Kind.DIAMETER_CLOSURE, moves);
    }

    /**
     * Internal decoder boundary: ownership of a fresh array is transferred with no retained alias.
     */
    static PrimitiveCertificate ofOwned(final Kind kind, final int[] moves) {
        return new PrimitiveCertificate(kind, moves, true);
    }

    public Kind kind() {
        return kind;
    }

    public int moveCount() {
        return moves.length;
    }

    public int moveAt(final int index) {
        return moves[index];
    }

    public int[] packedMoves() {
        return moves.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof PrimitiveCertificate result && kind == result.kind && Arrays.equals(moves, result.moves);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + Arrays.hashCode(moves);
    }
}
