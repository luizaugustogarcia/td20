package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;

import java.util.Objects;

/**
 * Budget-valid local support-clearing powers listed in the paper.
 */
public final class SupportClearing {

    private SupportClearing() {
    }

    public static boolean closes(final PrimitivePair pair) {
        return closingLength(pair) != 0;
    }

    /**
     * Primitive production path, using beta positions as the cyclic support order.
     */
    public static int closingLength(final PrimitivePair pair) {
        Objects.requireNonNull(pair);

        final int n = pair.size();
        if (n != KnownDiameters.ROOT_SYMBOLS) {
            return 0;
        }
        if (!pair.isFixedPointFree()) {
            throw new IllegalArgumentException("Contract algebraic fixed points before testing root support clearing");
        }

        final int[] position = new int[n], omega = new int[n];
        for (int i = 0; i < n; i++) {
            position[pair.betaAt(i)] = i;
        }
        for (int i = 0; i < n; i++) {
            omega[i] = position[pair.omegaImage(pair.betaAt(i))];
        }

        return closingLengthCanonicalUnchecked(omega);
    }

    /**
     * The caller supplies a validated fixed-point-free root with canonical beta.
     */
    static int closingLengthCanonicalUnchecked(final int[] omega) {
        int seen = 0;
        int chosen = 0;
        val support = new int[omega.length];
        for (int first = 0; first < omega.length; first++) {
            if ((seen & (1 << first)) != 0) {
                continue;
            }

            int x = first;
            int length = 0;
            int cycleMask = 0;
            do {
                cycleMask |= 1 << x;
                length++;
                x = omega[x];
            } while (x != first);

            seen |= cycleMask;
            if (length != 9 && length != 11 && length != 13 && length != 17 || chosen != 0 && chosen <= length) {
                continue;
            }

            int index = 0;
            for (int symbol = first; symbol < omega.length; symbol++) {
                if ((cycleMask & (1 << symbol)) != 0) {
                    support[index++] = symbol;
                }
            }
            if (admittedPower(omega, support, length)) {
                if ((length + 1) / 2 + KnownDiameters.knownDiameter(omega.length - length) > KnownDiameters.MAX_BUDGET) {
                    throw new IllegalStateException("Unsupported clearing budget");
                }

                chosen = length;
            }
        }

        return chosen;
    }

    static boolean admittedPower(final int[] omega, final int[] support, final int length) {
        return switch(length) {
            case 9 ->
                powerOnSupport(omega, support, length, 7);
            case 11 ->
                powerOnSupport(omega, support, length, 6);
            case 13 ->
                powerOnSupport(omega, support, length, 7)
                        || powerOnSupport(omega, support, length, 10)
                        || powerOnSupport(omega, support, length, 11);
            case 17 ->
                powerOnSupport(omega, support, length, 9)
                        || powerOnSupport(omega, support, length, 13)
                        || powerOnSupport(omega, support, length, 15);
            default ->
                false;
        };
    }

    private static boolean powerOnSupport(final int[] omega, final int[] support, final int length, final int exponent) {
        for (int rank = 0; rank < length; rank++) {
            if (omega[support[rank]] != support[(rank + exponent) % length]) {
                return false;
            }
        }

        return true;
    }

    public static boolean closes(final CyclicTargetPair pair) {
        return closingLength(pair) != 0;
    }

    public static int closingLength(final CyclicTargetPair pair) {
        if (pair.getBeta().size() != KnownDiameters.ROOT_SYMBOLS) {
            return 0;
        }
        if (CyclicTargetMoves.fixed(CyclicTargetMoves.images(pair)) != 0) {
            throw new IllegalArgumentException("Support-clearing roots require 21 nonfixed symbols");
        }

        int smallest = Integer.MAX_VALUE;
        for (val cycle : pair.getOmega()) {
            final int length = cycle.size();
            final int[] powers = switch(length) {
                case 9 ->
                    new int[] { 7 };
                case 11 ->
                    new int[] { 6 };
                case 13 ->
                    new int[] { 7, 10, 11 };
                case 17 ->
                    new int[] { 9, 13, 15 };
                default ->
                    new int[0];
            };
            if (powers.length == 0) {
                continue;
            }

            val support = new int[length];
            int at = 0;
            val member = new boolean[KnownDiameters.ROOT_SYMBOLS];
            for (final int symbol : cycle.getSymbols()) {
                member[symbol] = true;
            }
            for (final int symbol : pair.getBeta().getSymbols()) {
                if (member[symbol]) {
                    support[at++] = symbol;
                }
            }
            for (final int power : powers) {
                boolean match = true;
                for (int i = 0; i < length; i++) {
                    if (pair.getOmega().image(support[i]) != support[(i + power) % length]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    if ((length + 1) / 2 + KnownDiameters.knownDiameter(KnownDiameters.ROOT_SYMBOLS - length) > KnownDiameters.MAX_BUDGET) {
                        throw new IllegalStateException("Unsupported clearing budget");
                    }

                    smallest = Math.min(smallest, length);
                }
            }
        }

        return smallest == Integer.MAX_VALUE ? 0 : smallest;
    }
}
