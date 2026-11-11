package br.unb.cic.td20.certificate;

import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;

import java.util.List;

/** Replays a witness and verifies only intrinsic certificate obligations. */
public final class CertificateValidator {
    private CertificateValidator() {
    }

    public static void validate(final CyclicTargetPair pair,
                                final List<Cycle> moves,
                                final int maximumMoves) {
        validate(pair, SortingCertificate.complete(moves), maximumMoves);
    }

    /** Replays every transposition, then derives the required budget from the actual remainder. */
    public static int requiredBudget(final CyclicTargetPair pair, final SortingCertificate result) {
        final var moves = result.moves();
        final int n = pair.getBeta().size();
        final int[] omega = new int[n];
        for (int symbol = 0; symbol < n; symbol++) omega[symbol] = symbol;
        for (final var cycle : pair.getOmega()) {
            final int[] symbols = cycle.getSymbols();
            for (int i = 0; i < symbols.length; i++)
                omega[symbols[i]] = symbols[(i + 1) % symbols.length];
        }
        // Allocate replay state once. Omitted algebraic symbols remain fixed points.
        final int[] beta = moves.isEmpty() ? null : pair.getBeta().getSymbols().clone();
        final int[] positions = moves.isEmpty() ? null : new int[n];
        final int[] scratch = moves.isEmpty() ? null : new int[n];
        final int[] target = moves.isEmpty() ? null : new int[n];
        if (!moves.isEmpty()) {
            for (int i = 0; i < n; i++) positions[beta[i]] = i;
            final int[] targetSymbols = pair.getRho().getSymbols();
            for (int i = 0; i < n; i++)
                target[targetSymbols[i]] = targetSymbols[(i + 1) % n];
        }
        for (final var move : moves) {
            if (move.size() != 3)
                throw new IllegalArgumentException("A move must contain three distinct symbols");
            final int a = move.get(0), b = move.get(1), c = move.get(2);
            if (a == b || b == c || a == c)
                throw new IllegalArgumentException("A move must contain three distinct symbols");
            if (a < 0 || b < 0 || c < 0 || a >= n || b >= n || c >= n)
                throw new IllegalArgumentException("Move is not an ordered triple in the current cyclic order: " + move);
            final int first = positions[a], second = positions[b], third = positions[c];
            if (!(first < second && second < third))
                throw new IllegalArgumentException("Move is not an ordered triple in the current cyclic order: " + move);

            // omega' = omega * (a b c)^-1; swap beta's two adjacent blocks.
            final int oldA = omega[a], oldB = omega[b];
            omega[a] = omega[c];
            omega[b] = oldA;
            omega[c] = oldB;
            System.arraycopy(beta, first, scratch, 0, third - first);
            System.arraycopy(scratch, second - first, beta, first, third - second);
            System.arraycopy(scratch, 0, beta, first + third - second, second - first);
            for (int i = first; i < third; i++) positions[beta[i]] = i;

            for (int i = 0; i < n; i++) {
                if (omega[beta[(i + 1) % n]] != target[beta[i]])
                    throw new IllegalArgumentException("Certificate move does not preserve the cyclic target: " + move);
            }
        }
        int nonfixed = 0;
        for (int symbol = 0; symbol < n; symbol++) if (omega[symbol] != symbol) nonfixed++;
        if (result.kind() == SortingCertificate.Kind.COMPLETE) {
            if (nonfixed != 0) throw new IllegalArgumentException("Certificate does not sort the algebraic permutation");
            return moves.size();
        }
        int diameter = KnownDiameters.knownDiameter(nonfixed);
        if (diameter == Integer.MAX_VALUE) throw new IllegalArgumentException("Closure uses an unknown diameter");
        return Math.addExact(moves.size(), diameter);
    }

    public static void validate(final CyclicTargetPair pair, final SortingCertificate result, final int maximumMoves) {
        if (maximumMoves < 0 || requiredBudget(pair, result) > maximumMoves)
            throw new IllegalArgumentException("Sorting result does not close the requested move budget");
    }
}
