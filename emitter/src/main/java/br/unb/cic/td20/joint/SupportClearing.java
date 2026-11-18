package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;

import java.util.Objects;

/** The cited support powers whose clearing bounds fit the eleven-move root budget. */
public final class SupportClearing {
    private SupportClearing() { }

    public static boolean closes(final CyclicTargetPair pair) {
        return closingLength(pair) != 0;
    }

    public static boolean closes(final PrimitivePair pair) { return closingLength(pair) != 0; }

    /** Primitive production path, using beta positions as the cyclic support order. */
    public static int closingLength(final PrimitivePair pair) {
        Objects.requireNonNull(pair);
        final int n=pair.size();
        if (n!=KnownDiameters.ROOT_SYMBOLS) return 0;
        if (!pair.isFixedPointFree()) throw new IllegalArgumentException(
                "Contract algebraic fixed points before testing root support clearing");
        final int[] position=new int[n],omega=new int[n];
        for (int i=0;i<n;i++) position[pair.betaAt(i)]=i;
        for (int i=0;i<n;i++) omega[i]=position[pair.omegaImage(pair.betaAt(i))];
        return closingLengthCanonicalUnchecked(omega);
    }

    /**
     * Returns the least closing support length, or zero. These root closures
     * apply only on 21 symbols; a caller must contract fixed points first.
     */
    public static int closingLength(final CyclicTargetPair pair) {
        Objects.requireNonNull(pair);
        final int n = pair.getBeta().size();
        if (n != KnownDiameters.ROOT_SYMBOLS) return 0;
        final int[] beta = pair.getBeta().getSymbols();
        final int[] position = new int[n];
        for (int i = 0; i < n; i++) position[beta[i]] = i;
        final int[] omega = new int[n];
        for (int i = 0; i < n; i++) {
            omega[i] = position[pair.getOmega().image(beta[i])];
            if (omega[i] == i) throw new IllegalArgumentException(
                    "Contract algebraic fixed points before testing root support clearing");
        }
        return closingLengthCanonicalUnchecked(omega);
    }

    /** The caller supplies a validated fixed-point-free root with canonical beta. */
    static int closingLengthCanonicalUnchecked(final int[] omega) {
        int seen = 0;
        int chosen = 0;
        final int[] support = new int[omega.length];
        for (int first = 0; first < omega.length; first++) {
            if ((seen & (1 << first)) != 0) continue;
            int x = first;
            int length = 0;
            int cycleMask = 0;
            do {
                cycleMask |= 1 << x;
                length++;
                x = omega[x];
            } while (x != first);
            seen |= cycleMask;
            if (length != 9 && length != 11 && length != 13 && length != 17
                    || chosen != 0 && chosen <= length) continue;
            int index = 0;
            for (int symbol = first; symbol < omega.length; symbol++) {
                if ((cycleMask & (1 << symbol)) != 0) support[index++] = symbol;
            }
            if (admittedPower(omega, support, length)) chosen = length;
        }
        return chosen;
    }

    static boolean admittedPower(final int[] omega, final int[] support, final int length) {
        return switch (length) {
            case 9 -> powerOnSupport(omega, support, length, 7);
            case 11 -> powerOnSupport(omega, support, length, 6);
            case 13 -> powerOnSupport(omega, support, length, 7)
                    || powerOnSupport(omega, support, length, 10)
                    || powerOnSupport(omega, support, length, 11);
            case 17 -> powerOnSupport(omega, support, length, 9)
                    || powerOnSupport(omega, support, length, 13)
                    || powerOnSupport(omega, support, length, 15);
            default -> false;
        };
    }

    private static boolean powerOnSupport(final int[] omega, final int[] support,
                                          final int length, final int exponent) {
        for (int rank = 0; rank < length; rank++) {
            if (omega[support[rank]] != support[(rank + exponent) % length]) return false;
        }
        return true;
    }
}
