package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.permutation.PermutationGroups;
import br.unb.cic.td20.search.CommonOperations;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Exhaustive inverse supports for a gain-one, gain-two reduction prefix. */
public final class JointInverseReconstruction {
    private JointInverseReconstruction() { }
    public record SearchState(int maximumMoves, CyclicTargetPair pair) {
        public SearchState {
            Objects.requireNonNull(pair);
            if (maximumMoves < 9 || maximumMoves > 11) throw new IllegalArgumentException("Invalid reconstruction budget");
            if (JointMoves.fixed(JointMoves.images(pair)) != 0)
                throw new IllegalArgumentException("Reconstruction states must be contracted");
        }
    }
    private record Restoration(CyclicTargetPair pair, int[] fixed) { }
    public static void intermediates(final CyclicTargetPair endpoint, final Consumer<CyclicTargetPair> output) {
        requireDomain(endpoint,18);
        preimages(endpoint,2,output);
    }
    public static void roots(final CyclicTargetPair intermediate, final Consumer<CyclicTargetPair> output) {
        requireDomain(intermediate,20);
        preimages(intermediate,1,output);
    }
    /** Complete local inverse operation; production phases fix domains 18 and 20. */
    public static void preimages(final CyclicTargetPair child, final int gain,
                                 final Consumer<CyclicTargetPair> output) {
        final int symbols = child.getBeta().size();
        if (gain < 1 || gain > 2 || symbols + gain > 21
                || JointMoves.fixed(JointMoves.images(child)) != 0)
            throw new IllegalArgumentException("Expected a contracted child and a gain of one or two");
        restore(child,gain,state -> {
            if (gain == 2) {
                for (int symbol = 0; symbol < symbols; symbol++)
                    consider(state,state.fixed[0],state.fixed[1],symbol,2,output);
            } else {
                for (int a = 0; a < symbols-1; a++) for (int b = a+1; b < symbols; b++)
                    consider(state,state.fixed[0],a,b,1,output);
            }
        });
    }
    private static void requireDomain(final CyclicTargetPair pair, final int domain) {
        if (pair.getBeta().size() != domain || JointMoves.fixed(JointMoves.images(pair)) != 0)
            throw new IllegalArgumentException("Expected a fixed-point-free " + domain + "-symbol pair");
    }
    private static void consider(final Restoration state, final int a, final int b, final int c,
                                 final int expectedGain, final Consumer<CyclicTargetPair> output) {
        final var successor = state.pair;
        final var inverseMove = ordered(successor.getBeta(),a,b,c);
        final var omega = PermutationGroups.computeProduct(successor.getOmega(),inverseMove.getInverse());
        final var beta = CommonOperations.applyTranspositionOptimized(successor.getBeta(),inverseMove);
        final var parent = CyclicTargetPair.of(omega,beta);
        final int[] images = JointMoves.images(parent);
        if (JointMoves.fixed(images) != 0) return;
        if (JointMoves.totallyUnoriented(images,beta.getSymbols())) return;
        final var forwardMove = ordered(beta,a,b,c);
        final int[] forwardImages = JointMoves.apply(images,forwardMove.get(0),forwardMove.get(1),forwardMove.get(2));
        if (!Arrays.equals(forwardImages,JointMoves.images(successor))
                || !CommonOperations.applyTranspositionOptimized(beta,forwardMove).equals(successor.getBeta()))
            throw new IllegalStateException("Inverse support failed exact forward replay");
        if (JointMoves.fixed(forwardImages) != expectedGain || JointMoves.odd(forwardImages) != JointMoves.odd(images)+2)
            return;
        output.accept(parent);
    }
    private static Cycle ordered(final Cycle beta, final int a, final int b, final int c) {
        return CommonOperations.areSymbolsInCyclicOrder(beta,a,b,c) ? Cycle.of(a,b,c) : Cycle.of(a,c,b);
    }
    private static void restore(final CyclicTargetPair pair, final int count, final Consumer<Restoration> output) {
        insert(pair,new int[count],0,output);
    }
    private static void insert(final CyclicTargetPair pair, final int[] restored, final int depth,
                               final Consumer<Restoration> output) {
        if (depth == restored.length) {
            // Sequential insertion labels are retained in increasing cyclic
            // position, selecting one labeling of each gap-multiplicity vector.
            for (int i = 1; i < restored.length; i++)
                if (pair.getBeta().indexOf(restored[i-1]) > pair.getBeta().indexOf(restored[i])) return;
            output.accept(new Restoration(pair,restored.clone()));
            return;
        }
        final int symbol = pair.getBeta().size(); restored[depth] = symbol;
        final var omega = new MulticyclePermutation(pair.getOmega()); omega.add(Cycle.of(symbol));
        for (int gap = 0; gap < symbol; gap++) {
            final int[] beta = new int[symbol+1];
            int cursor = 0;
            for (int i = 0; i < symbol; i++) {
                beta[cursor++] = pair.getBeta().get(i);
                if (i == gap) beta[cursor++] = symbol;
            }
            insert(CyclicTargetPair.of(omega,Cycle.of(beta)),restored,depth+1,output);
        }
    }
}
