package br.unb.cic.td20.joint;

import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.permutation.Cycle;
import br.unb.cic.td20.permutation.MulticyclePermutation;
import br.unb.cic.td20.permutation.PermutationGroups;
import br.unb.cic.td20.search.CommonOperations;
import java.util.Arrays;
import java.util.function.Consumer;

/** Checker-owned restoration by sequential insertion and arbitrary inverse supports. */
public final class ErikssonInverseReconstruction {
    private ErikssonInverseReconstruction() { }
    public static void preimages(CyclicTargetPair child,int gain,Consumer<CyclicTargetPair> output) {
        if (gain<0 || gain>3 || child.getBeta().size()+gain>ErikssonRoute.ROOT_SYMBOLS
                || CyclicTargetMoves.fixed(CyclicTargetMoves.images(child))!=0)
            throw new IllegalArgumentException("Expected a contracted child and inverse gain 0..3");
        insert(child,child.getBeta().size(),gain,new int[gain],0,output);
        insert(swap(child),child.getBeta().size(),gain,new int[gain],0,parent -> output.accept(swap(parent)));
    }
    private static CyclicTargetPair swap(CyclicTargetPair pair) {
        var inverse = new MulticyclePermutation();
        for (var cycle : pair.getOmega())
            inverse.add(Cycle.of(cycle.getInverse().getSymbols().clone()));
        // omega^-1 * rho = beta; all owned arrays are fresh.
        return CyclicTargetPair.ofKnownTargetOwned(inverse,
                Cycle.of(pair.getRho().getSymbols().clone()),
                Cycle.of(pair.getBeta().getSymbols().clone()));
    }
    private static void insert(CyclicTargetPair state,int original,int gain,int[] added,int depth,Consumer<CyclicTargetPair> output) {
        if (depth==gain) {
            for (int i=1;i<added.length;i++) if (state.getBeta().indexOf(added[i-1])>state.getBeta().indexOf(added[i])) return;
            int n=state.getBeta().size();
            for (int a=0;a<n-2;a++) for (int b=a+1;b<n-1;b++) for (int c=b+1;c<n;c++) {
                int restored=(a>=original ? 1:0)+(b>=original ? 1:0)+(c>=original ? 1:0);
                if (restored!=gain) continue;
                consider(state,a,b,c,gain,output);
            }
            return;
        }
        int n=state.getBeta().size(); added[depth]=n;
        var omega=new MulticyclePermutation(state.getOmega()); omega.add(Cycle.of(n));
        for (int gap=0;gap<n;gap++) {
            int[] beta=new int[n+1]; int cursor=0;
            for (int i=0;i<n;i++) { beta[cursor++]=state.getBeta().get(i); if (i==gap) beta[cursor++]=n; }
            insert(CyclicTargetPair.of(omega,Cycle.of(beta)),original,gain,added,depth+1,output);
        }
    }
    private static void consider(CyclicTargetPair child,int a,int b,int c,int gain,Consumer<CyclicTargetPair> output) {
        Cycle inverse=ordered(child.getBeta(),a,b,c);
        var omega=PermutationGroups.computeProduct(child.getOmega(),inverse.getInverse());
        // computeProduct includes every fixed point; reject before materializing beta.
        for (var cycle : omega) if (cycle.size() == 1) return;
        Cycle beta=CommonOperations.applyTranspositionOptimized(child.getBeta(),inverse);
        // An inverse transposition preserves omega * beta = rho.
        // Retain the exact forward gain and pair replay below.
        CyclicTargetPair parent=CyclicTargetPair.ofKnownTargetOwned(omega,beta,
                Cycle.of(child.getRho().getSymbols().clone()));
        int[] images=CyclicTargetMoves.images(parent);
        if (CyclicTargetMoves.fixed(images)!=0) return;
        Cycle forward=ordered(beta,a,b,c);
        if (CyclicTargetMoves.gain(images,forward.get(0),forward.get(1),forward.get(2))!=gain) return;
        if (!Arrays.equals(CyclicTargetMoves.apply(images,forward.get(0),forward.get(1),forward.get(2)),CyclicTargetMoves.images(child))
                || !CommonOperations.applyTranspositionOptimized(beta,forward).equals(child.getBeta()))
            throw new IllegalStateException("Independent two-sided inverse failed forward replay");
        output.accept(parent);
    }
    private static Cycle ordered(Cycle beta,int a,int b,int c) {
        return CommonOperations.areSymbolsInCyclicOrder(beta,a,b,c) ? Cycle.of(a,b,c):Cycle.of(a,c,b);
    }
}
