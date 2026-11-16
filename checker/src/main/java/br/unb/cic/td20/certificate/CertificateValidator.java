package br.unb.cic.td20.certificate;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.permutation.PermutationGroups;
import br.unb.cic.td20.search.CommonOperations;
import br.unb.cic.td20.joint.KnownDiameters;

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
        final var target = pair.getRho();
        Cycle beta = pair.getBeta();
        MulticyclePermutation omega = pair.getOmega();
        for (final var move : moves) {
            requireApplicable(beta, move);
            omega = PermutationGroups.computeProduct(omega, move.getInverse());
            beta = CommonOperations.applyTranspositionOptimized(beta, move);
            final var currentTarget = PermutationGroups.computeProduct(true, beta.size(), omega, beta);
            if (!currentTarget.equals(new MulticyclePermutation(target))) {
                throw new IllegalArgumentException("Certificate move does not preserve the cyclic target: " + move);
            }
        }
        if (result.kind() == SortingCertificate.Kind.COMPLETE) {
            if (!omega.isIdentity()) throw new IllegalArgumentException("Certificate does not sort the algebraic permutation");
            return moves.size();
        }
        int nonfixed = 0;
        for (int symbol = 0; symbol < beta.size(); symbol++) if (omega.image(symbol) != symbol) nonfixed++;
        int diameter = KnownDiameters.knownDiameter(nonfixed);
        if (diameter == Integer.MAX_VALUE) throw new IllegalArgumentException("Closure uses an unknown diameter");
        return Math.addExact(moves.size(), diameter);
    }

    public static void validate(final CyclicTargetPair pair, final SortingCertificate result, final int maximumMoves) {
        validatedRequiredBudget(pair, result, maximumMoves);
    }

    /** Replays once, validates the upper bound, and returns it for requested-budget comparisons. */
    public static int validatedRequiredBudget(final CyclicTargetPair pair, final SortingCertificate result,
                                              final int maximumMoves) {
        if (maximumMoves < 0)
            throw new IllegalArgumentException("Sorting result does not close the requested move budget");
        final int required = requiredBudget(pair, result);
        if (required > maximumMoves)
            throw new IllegalArgumentException("Sorting result does not close the requested move budget");
        return required;
    }

    private static void requireApplicable(final Cycle beta, final Cycle move) {
        if (move.size() != 3 || move.get(0) == move.get(1) || move.get(1) == move.get(2)
                || move.get(0) == move.get(2)) {
            throw new IllegalArgumentException("A move must contain three distinct symbols");
        }
        final int first = beta.indexOf(move.get(0));
        final int second = beta.indexOf(move.get(1));
        final int third = beta.indexOf(move.get(2));
        if (first < 0 || second < 0 || third < 0 || !(first < second && second < third)) {
            throw new IllegalArgumentException("Move is not an ordered triple in the current cyclic order: " + move);
        }
    }
}
