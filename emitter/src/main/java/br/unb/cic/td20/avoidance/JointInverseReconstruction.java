package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.joint.JointReductionAnalysis;
import br.unb.cic.td20.joint.JointReductionAnalysis.Move;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import java.util.Arrays;
import java.util.function.Consumer;

/** Restores exactly the deleted support of each qualifying reduction. */
public final class JointInverseReconstruction {
    private JointInverseReconstruction() { }

    public static void intermediates(CyclicTargetPair endpoint, Consumer<CyclicTargetPair> output) {
        if (endpoint.getBeta().size() != 18) throw new IllegalArgumentException("Expected an 18-symbol endpoint");
        preimages(endpoint, 2, output);
    }

    public static void roots(CyclicTargetPair intermediate, Consumer<CyclicTargetPair> output) {
        if (intermediate.getBeta().size() != 20) throw new IllegalArgumentException("Expected a 20-symbol intermediate");
        preimages(intermediate, 1, output);
    }

    /** Domain-independent support enumeration, also used for exhaustive small-domain checks. */
    public static void preimages(CyclicTargetPair child, int restoredCount, Consumer<CyclicTargetPair> output) {
        if (restoredCount != 1 && restoredCount != 2) throw new IllegalArgumentException("Restore one or two fixed points");
        for (int symbol : child.getBeta().getSymbols()) {
            if (child.getOmega().image(symbol) == symbol)
                throw new IllegalArgumentException("Contract fixed points before reconstruction");
        }
        int m = child.getBeta().size(), n = m + restoredCount;
        int[] omega = new int[n];
        for (int x = 0; x < n; x++) omega[x] = x < m ? child.getOmega().image(x) : x;
        // Keep the first old symbol anchored. Ordered restored labels select
        // exactly one labeling of each cyclic-gap multiplicity vector.
        for (int first = 1; first < n; first++) {
            if (restoredCount == 1) restore(child, omega, first, -1, output);
            else for (int second = first + 1; second < n; second++) restore(child, omega, first, second, output);
        }
    }

    private static void restore(CyclicTargetPair child, int[] omega, int first, int second,
                                Consumer<CyclicTargetPair> output) {
        int m = child.getBeta().size();
        int[] beta = new int[omega.length];
        for (int i = 0, old = 0; i < beta.length; i++)
            beta[i] = i == first ? m : i == second ? m + 1 : child.getBeta().get(old++);
        if (second >= 0) {
            for (int x = 0; x < m; x++) reverse(omega, beta, m, m+1, x, 2, output);
        } else {
            for (int x = 0; x < m-1; x++) for (int y = x+1; y < m; y++)
                reverse(omega, beta, m, x, y, 1, output);
        }
    }

    private static void reverse(int[] successorOmega, int[] successorBeta, int a, int b, int c,
                                int gain, Consumer<CyclicTargetPair> output) {
        Move inverse = ordered(successorBeta, a, b, c);
        int[] omega = JointReductionAnalysis.applyToOmega(successorOmega, inverse);
        for (int x = 0; x < omega.length; x++) if (omega[x] == x) return;
        int[] beta = JointReductionAnalysis.applyToBeta(successorBeta, inverse);
        if (gain == 2 && JointReductionAnalysis.isTotallyUnoriented(omega, beta)) return;
        Move forward = ordered(beta, a, b, c);
        int[] replay = JointReductionAnalysis.applyToOmega(omega, forward);
        if (!Arrays.equals(replay, successorOmega)
                || !Arrays.equals(JointReductionAnalysis.applyToBeta(beta, forward), successorBeta))
            throw new IllegalStateException("Reconstructed support failed forward replay");
        if (JointReductionAnalysis.gain(omega, forward) != gain
                || JointReductionAnalysis.oddCycleCount(replay) != JointReductionAnalysis.oddCycleCount(omega) + 2)
            return;
        output.accept(pair(omega, beta));
    }

    private static Move ordered(int[] beta, int a, int b, int c) {
        int[] support = new int[3];
        int count = 0;
        for (int x : beta) if (x == a || x == b || x == c) support[count++] = x;
        if (count != 3) throw new IllegalArgumentException("Support is outside beta");
        return new Move(support[0], support[1], support[2]);
    }

    private static CyclicTargetPair pair(int[] images, int[] beta) {
        var omega = new MulticyclePermutation();
        boolean[] seen = new boolean[images.length];
        for (int x = 0; x < images.length; x++) if (!seen[x]) {
            int[] symbols = new int[images.length]; int size = 0, y = x;
            do { symbols[size++] = y; seen[y] = true; y = images[y]; } while (y != x);
            omega.add(Cycle.of(Arrays.copyOf(symbols, size)));
        }
        return CyclicTargetPair.ofOwned(omega, Cycle.of(beta));
    }
}
