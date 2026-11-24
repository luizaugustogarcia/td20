package br.unb.cic.td20.certificate;

import br.unb.cic.td20.compat.LegacyCertificates;
import br.unb.cic.td20.compat.LegacyPairs;
import br.unb.cic.td20.joint.KnownDiameters;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.permutation.Cycle;
import lombok.val;

import java.util.List;

/**
 * Replays a witness and verifies only intrinsic certificate obligations.
 */
public final class CertificateValidator {

    private static final ThreadLocal<ReplayWorkspace> WORKSPACE = ThreadLocal.withInitial(ReplayWorkspace::new);

    private CertificateValidator() {
    }

    public static void validate(final CyclicTargetPair pair, final List<Cycle> moves, final int maximumMoves) {
        validate(pair, SortingCertificate.complete(moves), maximumMoves);
    }

    /**
     * Replays every transposition, then derives the required budget from the actual remainder.
     */
    public static int requiredBudget(final CyclicTargetPair pair, final SortingCertificate result) {
        val primitive = LegacyPairs.fromLegacy(pair);
        // The legacy trusted-target factory can carry an unchecked target. Preserve
        // its replay obligation before converting into the fully checked primitive type.
        if (!result.moves().isEmpty()) {
            val rho = pair.getRho();
            for (int i = 0; i < primitive.size(); i++) {
                final int symbol = primitive.rhoAt(i);
                if (rho.image(symbol) != primitive.rhoAt(i + 1 == primitive.size() ? 0 : i + 1)) {
                    throw new IllegalArgumentException("Certificate move does not preserve the cyclic target");
                }
            }
        }

        return requiredBudget(primitive, LegacyCertificates.fromLegacy(result));
    }

    /**
     * Replays packed moves using one worker-owned workspace; the source pair is immutable.
     */
    public static int requiredBudget(final PrimitivePair pair, final PrimitiveCertificate result) {
        final int n = pair.size();
        val work = WORKSPACE.get();
        final int[] omega = work.omega, beta = work.beta, positions = work.positions;
        final int[] scratch = work.scratch, target = work.target;
        for (int symbol = 0; symbol < n; symbol++) {
            omega[symbol] = pair.omegaImage(symbol);
        }
        if (result.moveCount() != 0) {
            for (int i = 0; i < n; i++) {
                beta[i] = pair.betaAt(i);
                positions[beta[i]] = i;
                target[pair.rhoAt(i)] = pair.rhoAt(i + 1 == n ? 0 : i + 1);
            }
        }
        for (int step = 0; step < result.moveCount(); step++) {
            final int move = result.moveAt(step);
            final int a = move & 31, b = (move >>> 5) & 31, c = (move >>> 10) & 31;
            if (a < 0 || b < 0 || c < 0 || a >= n || b >= n || c >= n) {
                throw new IllegalArgumentException("Move lies outside the current-cycle domain");
            }

            final int first = positions[a], second = positions[b], third = positions[c];
            if (!(first < second && second < third)) {
                throw new IllegalArgumentException("Move is not an ordered triple in the current cyclic order");
            }

            // omega' = omega * (a b c)^-1; swap beta's two adjacent blocks.
            final int oldA = omega[a], oldB = omega[b];
            omega[a] = omega[c];
            omega[b] = oldA;
            omega[c] = oldB;
            System.arraycopy(beta, first, scratch, 0, third - first);
            System.arraycopy(scratch, second - first, beta, first, third - second);
            System.arraycopy(scratch, 0, beta, first + third - second, second - first);
            for (int i = first; i < third; i++) {
                positions[beta[i]] = i;
            }

            for (int i = 0; i < n; i++) {
                if (omega[beta[i + 1 == n ? 0 : i + 1]] != target[beta[i]]) {
                    throw new IllegalArgumentException("Certificate move does not preserve the cyclic target");
                }
            }
        }

        int nonfixed = 0;
        for (int symbol = 0; symbol < n; symbol++) {
            if (omega[symbol] != symbol) {
                nonfixed++;
            }
        }
        if (result.kind() == PrimitiveCertificate.Kind.COMPLETE) {
            if (nonfixed != 0) {
                throw new IllegalArgumentException("Certificate does not sort the algebraic permutation");
            }

            return result.moveCount();
        }

        final int diameter = KnownDiameters.knownDiameter(nonfixed);
        if (diameter == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Closure uses an unknown diameter");
        }

        return Math.addExact(result.moveCount(), diameter);
    }

    public static void validate(final CyclicTargetPair pair, final SortingCertificate result, final int maximumMoves) {
        if (maximumMoves < 0 || requiredBudget(pair, result) > maximumMoves) {
            throw new IllegalArgumentException("Sorting result does not close the requested move budget");
        }
    }

    public static void validate(final PrimitivePair pair, final PrimitiveCertificate result, final int maximumMoves) {
        if (maximumMoves < 0 || requiredBudget(pair, result) > maximumMoves) {
            throw new IllegalArgumentException("Sorting result does not close the requested move budget");
        }
    }

    private static final class ReplayWorkspace {

        final int[] omega = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[] beta = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[] positions = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[] scratch = new int[KnownDiameters.ROOT_SYMBOLS];
        final int[] target = new int[KnownDiameters.ROOT_SYMBOLS];
    }
}
